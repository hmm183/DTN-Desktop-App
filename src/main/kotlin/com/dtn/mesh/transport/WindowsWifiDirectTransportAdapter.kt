package com.dtn.mesh.transport

import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.receiver.DtnWireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Windows Wi-Fi Direct Transport Adapter.
 *
 * Runs a ServerSocket on port 9734 to accept high-speed bulk queue sync connections
 * from Android phones and other desktop peers.
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
    private val activeSockets = ConcurrentHashMap<String, Socket>()

    fun start() {
        scope.launch {
            startServer()
        }
    }

    fun stop() {
        _isListening.value = false
        runCatching { serverSocket?.close() }
        activeSockets.values.forEach { runCatching { it.close() } }
        activeSockets.clear()
    }

    private fun startServer() {
        try {
            val ss = ServerSocket(listenPort)
            serverSocket = ss
            _isListening.value = true
            println("[WifiDirect] Listening for P2P connections on port $listenPort")

            while (scope.isActive && !ss.isClosed) {
                try {
                    val client = ss.accept()
                    client.soTimeout = 10000
                    scope.launch {
                        handleClient(client)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            println("[WifiDirect] Server error: ${e.message}")
        }
    }

    private fun handleClient(socket: Socket) {
        val remoteIp = socket.inetAddress.hostAddress
        try {
            val inStream = socket.getInputStream()
            val outStream = socket.getOutputStream()

            // Mutual HELLO exchange: [4B wireLen][wire][4B idLen][id]
            // Send our HELLO
            val idBytes = localNodeId.value.toByteArray(Charsets.UTF_8)
            val hello = ByteBuffer.allocate(8 + idBytes.size).order(ByteOrder.BIG_ENDIAN)
            hello.putInt(0) // wireLen = 0 means HELLO
            hello.putInt(idBytes.size)
            hello.put(idBytes)
            outStream.write(hello.array())
            outStream.flush()

            // Read peer's HELLO
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

                        // Read any subsequent bundle
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
