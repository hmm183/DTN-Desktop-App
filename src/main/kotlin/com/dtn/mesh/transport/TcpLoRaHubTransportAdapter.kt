package com.dtn.mesh.transport

import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.receiver.DtnWireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TCP transport adapter for connecting to a Heltec LoRa hub SoftAP (default 192.168.4.1:9740).
 */
class TcpLoRaHubTransportAdapter(
    private val localNodeId: NodeId,
    private val hubHost: String = "192.168.4.1",
    private val hubPort: Int = 9740,
    private val onMessageReceived: (DtnMessage, String) -> Unit,
    private val onPeerStatusChanged: (String, Boolean, String) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null

    var hubId: NodeId? = null
        private set

    fun start() {
        scope.launch {
            reconnectLoop()
        }
    }

    fun stop() {
        closeSocket()
    }

    private suspend fun reconnectLoop() {
        while (scope.isActive) {
            if (socket == null || socket!!.isClosed || !socket!!.isConnected) {
                _isConnected.value = false
                tryConnect()
            }
            delay(5000)
        }
    }

    private fun tryConnect() {
        try {
            val s = Socket()
            s.connect(InetSocketAddress(hubHost, hubPort), 4000)
            socket = s
            outputStream = s.getOutputStream()
            _isConnected.value = true
            println("[TcpLoRaHub] Connected to LoRa Hub at $hubHost:$hubPort")

            // Send REGISTER
            val buf = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN)
            buf.put(0x01.toByte()) // REGISTER
            buf.putShort(4.toShort())
            buf.putInt(localNodeId.toNodeNum32())
            sendRaw(buf.array())

            scope.launch {
                readLoop(s.getInputStream())
            }

            scope.launch {
                pingLoop()
            }
        } catch (e: Exception) {
            // Hub not reachable on Wi-Fi
        }
    }

    private suspend fun pingLoop() {
        while (scope.isActive && _isConnected.value) {
            delay(30_000L)
            val ping = ByteBuffer.allocate(3).order(ByteOrder.BIG_ENDIAN)
            ping.put(0x04.toByte()) // PING
            ping.putShort(0.toShort())
            sendRaw(ping.array())
        }
    }

    fun sendBundle(bundle: DtnMessage): Boolean {
        return try {
            val wire = DtnWireCodec.encode(bundle)
            val frame = ByteBuffer.allocate(3 + wire.size).order(ByteOrder.BIG_ENDIAN)
            frame.put(0x02.toByte()) // BUNDLE
            frame.putShort(wire.size.toShort())
            frame.put(wire)
            sendRaw(frame.array())
            true
        } catch (e: Exception) {
            println("[TcpLoRaHub] Failed to send bundle: ${e.message}")
            false
        }
    }

    @Synchronized
    private fun sendRaw(bytes: ByteArray) {
        try {
            outputStream?.write(bytes)
            outputStream?.flush()
        } catch (e: Exception) {
            closeSocket()
        }
    }

    private fun readLoop(inputStream: InputStream) {
        val header = ByteArray(3)
        while (scope.isActive && socket?.isConnected == true && !socket!!.isClosed) {
            try {
                if (!readFully(inputStream, header, 3)) break
                val type = header[0].toInt() and 0xFF
                val len = ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)

                if (len < 0 || len > 2048) break

                val payload = ByteArray(len)
                if (!readFully(inputStream, payload, len)) break

                when (type) {
                    0x03 -> { // HELLO
                        if (payload.size >= 4) {
                            val hubNum = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).int
                            val hid = NodeId.fromNodeNum(hubNum)
                            hubId = hid
                            onPeerStatusChanged(hid.value, true, "LoRa Hub (Wi-Fi)")
                        }
                    }
                    0x02 -> { // BUNDLE
                        try {
                            val bundle = DtnWireCodec.decode(payload, rssi = -40, snr = 10f) ?: return
                            onMessageReceived(bundle, hubId?.value ?: "WIFI_HUB")
                        } catch (_: Exception) {}
                    }
                    0x05, 0x06 -> { // ROSTER
                        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                        while (bb.remaining() >= 4) {
                            val nodeNum = bb.int
                            val pid = NodeId.fromNodeNum(nodeNum)
                            if (pid != localNodeId) {
                                onPeerStatusChanged(pid.value, true, "Online via LoRa Hub")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                break
            }
        }
        closeSocket()
    }

    private fun readFully(inputStream: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val r = inputStream.read(buf, read, len - read)
            if (r < 0) return false
            read += r
        }
        return true
    }

    private fun closeSocket() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        outputStream = null
        _isConnected.value = false
    }
}
