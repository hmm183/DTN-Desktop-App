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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Windows Wi-Fi Direct & LAN Subnet Transport Adapter.
 *
 * Implements:
 * 1. TCP Server on port 9734 matching Android's exact framing:
 *    [4B wireLength][wire bytes][4B idLen][sender node id]
 * 2. UDP Subnet Beaconing on port 9734 to auto-discover peers when on the same Wi-Fi router.
 */
class WindowsWifiDirectTransportAdapter(
    private val localNodeId: NodeId,
    private val listenPort: Int = 9734,
    private val onMessageReceived: (DtnMessage, String) -> Unit,
    private val onPeerStatusChanged: (String, Boolean, String) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _connectedPeers = MutableStateFlow<Map<String, String>>(emptyMap()) // peerId -> IP
    val connectedPeers: StateFlow<Map<String, String>> = _connectedPeers.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private val activeSockets = ConcurrentHashMap<String, Socket>()

    fun start() {
        scope.launch {
            startTcpServer()
        }
        scope.launch {
            startUdpBeaconListener()
        }
        scope.launch {
            udpBeaconBroadcastLoop()
        }
    }

    fun stop() {
        _isListening.value = false
        runCatching { serverSocket?.close() }
        runCatching { udpSocket?.close() }
        activeSockets.values.forEach { runCatching { it.close() } }
        activeSockets.clear()
    }

    private fun startTcpServer() {
        try {
            val ss = ServerSocket(listenPort)
            serverSocket = ss
            _isListening.value = true
            println("[WifiDirect] TCP Server listening on port $listenPort")

            while (scope.isActive && !ss.isClosed) {
                try {
                    val client = ss.accept()
                    client.soTimeout = 15000
                    scope.launch {
                        handleClient(client)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            println("[WifiDirect] TCP Server error: ${e.message}")
        }
    }

    private fun startUdpBeaconListener() {
        try {
            val udp = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(listenPort))
            }
            udpSocket = udp
            val buf = ByteArray(128)
            println("[WifiDirect] UDP Beacon listener bound to port $listenPort")

            while (scope.isActive && !udp.isClosed) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    udp.receive(packet)
                    if (packet.length >= 8 && buf[0] == 0xD7.toByte() && buf[1] == 0x02.toByte()) {
                        val bb = ByteBuffer.wrap(buf, 2, packet.length - 2).order(ByteOrder.BIG_ENDIAN)
                        val peerNodeNum = bb.int
                        val peerPort = bb.short.toInt() and 0xFFFF
                        val peerHex = String.format("%08x", peerNodeNum)
                        val peerId = "!$peerHex"

                        if (peerId != localNodeId.value) {
                            val peerIp = packet.address.hostAddress
                            val current = _connectedPeers.value.toMutableMap()
                            if (current[peerId] != peerIp) {
                                current[peerId] = peerIp
                                _connectedPeers.value = current
                                onPeerStatusChanged(peerId, true, "Local Wi-Fi ($peerIp)")
                                println("[WifiDirect] Heard LAN beacon from $peerId at $peerIp:$peerPort")

                                // Trigger HELLO handshake
                                scope.launch {
                                    sendHello(peerIp, peerPort)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            println("[WifiDirect] UDP listener notice: ${e.message}")
        }
    }

    private suspend fun udpBeaconBroadcastLoop() {
        delay(1000)
        while (scope.isActive) {
            try {
                val socket = DatagramSocket()
                socket.broadcast = true
                val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                buf.put(0xD7.toByte())
                buf.put(0x02.toByte())
                buf.putInt(localNodeId.toNodeNum32())
                buf.putShort(listenPort.toShort())

                val bytes = buf.array()
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(bytes, bytes.size, broadcastAddr, listenPort)
                socket.send(packet)
                socket.close()
            } catch (_: Exception) {}
            delay(5000)
        }
    }

    private fun sendHello(targetIp: String, port: Int) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(targetIp, port), 2000)
            val os = socket.getOutputStream()

            val idBytes = localNodeId.value.toByteArray(Charsets.UTF_8)
            val hello = ByteBuffer.allocate(8 + idBytes.size).order(ByteOrder.BIG_ENDIAN)
            hello.putInt(0) // wireLen = 0 (HELLO)
            hello.putInt(idBytes.size)
            hello.put(idBytes)
            os.write(hello.array())
            os.flush()
            socket.close()
        } catch (_: Exception) {}
    }

    private fun handleClient(socket: Socket) {
        val remoteIp = socket.inetAddress.hostAddress
        try {
            val inStream = socket.getInputStream()
            val outStream = socket.getOutputStream()

            // Mutual HELLO exchange: [4B wireLen][wire][4B idLen][id]
            val idBytes = localNodeId.value.toByteArray(Charsets.UTF_8)
            val hello = ByteBuffer.allocate(8 + idBytes.size).order(ByteOrder.BIG_ENDIAN)
            hello.putInt(0)
            hello.putInt(idBytes.size)
            hello.put(idBytes)
            outStream.write(hello.array())
            outStream.flush()

            val lenBuf = ByteArray(8)
            if (readFully(inStream, lenBuf, 8)) {
                val bb = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN)
                val wireLen = bb.int
                val idLen = bb.int
                if (idLen in 1..64) {
                    val pidBytes = ByteArray(idLen)
                    if (readFully(inStream, pidBytes, idLen)) {
                        val peerId = String(pidBytes, Charsets.UTF_8)
                        activeSockets[peerId] = socket
                        val updated = _connectedPeers.value.toMutableMap()
                        updated[peerId] = remoteIp
                        _connectedPeers.value = updated
                        onPeerStatusChanged(peerId, true, "Wi-Fi Direct ($remoteIp)")

                        if (wireLen > 0) {
                            val wire = ByteArray(wireLen)
                            if (readFully(inStream, wire, wireLen)) {
                                val bundle = DtnWireCodec.decode(wire, rssi = -30, snr = 20f)
                                if (bundle != null) {
                                    onMessageReceived(bundle, peerId)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("[WifiDirect] Connection dropped ($remoteIp): ${e.message}")
        }
    }

    fun sendBundle(bundle: DtnMessage, targetIp: String): Boolean {
        return try {
            val wire = DtnWireCodec.encode(bundle)
            val socket = Socket()
            socket.connect(InetSocketAddress(targetIp, listenPort), 3000)
            val os = socket.getOutputStream()

            val idBytes = localNodeId.value.toByteArray(Charsets.UTF_8)
            val frame = ByteBuffer.allocate(8 + wire.size + idBytes.size).order(ByteOrder.BIG_ENDIAN)
            frame.putInt(wire.size)
            frame.put(wire)
            frame.putInt(idBytes.size)
            frame.put(idBytes)

            os.write(frame.array())
            os.flush()
            socket.close()
            true
        } catch (e: Exception) {
            println("[WifiDirect] Failed to send bundle to $targetIp: ${e.message}")
            false
        }
    }

    private fun readFully(inputStream: InputStream, buf: ByteArray, len: Int): Boolean {
        var readTotal = 0
        while (readTotal < len) {
            val r = inputStream.read(buf, readTotal, len - readTotal)
            if (r < 0) return false
            readTotal += r
        }
        return true
    }
}
