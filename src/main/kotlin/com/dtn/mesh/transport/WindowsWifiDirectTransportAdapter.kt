package com.dtn.mesh.transport

import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.receiver.DtnWireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
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
 *    `[4B wireLength][wire bytes][4B idLen][sender node id]`
 * 2. Automatic Local LAN Subnet Discovery (sweep /24 on port 9734) & UDP Beaconing.
 * 3. Direct IP Connect for manual or static hotspot pairing (supports 192.168.49.1 for Wi-Fi Direct GO).
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

    private val _isSweeping = MutableStateFlow(false)
    val isSweeping: StateFlow<Boolean> = _isSweeping.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private val lastProbedIp = ConcurrentHashMap<String, Long>()

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
        scope.launch {
            subnetSweepLoop()
        }
    }

    fun stop() {
        _isListening.value = false
        runCatching { serverSocket?.close() }
        runCatching { udpSocket?.close() }
    }

    fun getLocalIpAddresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    val host = addr.hostAddress
                    if (host.contains(".") && !host.startsWith("127.")) {
                        val name = iface.displayName.take(24)
                        result.add("$host ($name)")
                    }
                }
            }
        } catch (_: Exception) {}
        return result
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
                    client.soTimeout = 8000
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

    private fun handleClient(socket: Socket) {
        val remoteIp = socket.inetAddress?.hostAddress ?: return
        try {
            socket.soTimeout = 8000
            val dis = DataInputStream(socket.getInputStream())
            val wireLen = dis.readInt()

            val wireBytes = if (wireLen > 0 && wireLen < 65536) {
                ByteArray(wireLen).also { dis.readFully(it) }
            } else {
                ByteArray(0)
            }

            val idLen = dis.readInt()
            if (idLen in 1..64) {
                val idBytes = ByteArray(idLen).also { dis.readFully(it) }
                val peerId = String(idBytes, Charsets.UTF_8)
                socket.close()

                if (peerId != localNodeId.value) {
                    val isNew = !_connectedPeers.value.containsKey(peerId)
                    val updated = _connectedPeers.value.toMutableMap()
                    updated[peerId] = remoteIp
                    _connectedPeers.value = updated
                    onPeerStatusChanged(peerId, true, "Wi-Fi Direct / LAN ($remoteIp)")

                    if (isNew) {
                        println("[WifiDirect] Linked with peer $peerId at $remoteIp")
                        // Send HELLO back
                        scope.launch {
                            sendHello(remoteIp, listenPort)
                        }
                    }

                    if (wireBytes.isNotEmpty()) {
                        val bundle = DtnWireCodec.decode(wireBytes, rssi = -30, snr = 20f)
                        if (bundle != null && bundle.originNodeId != localNodeId) {
                            onMessageReceived(bundle, "Wi-Fi ($remoteIp)")
                        }
                    }
                }
            } else {
                socket.close()
            }
        } catch (e: Exception) {
            runCatching { socket.close() }
        }
    }

    fun connectToPeerIp(targetIp: String, port: Int = listenPort): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(targetIp, port), 2500)
            val os = DataOutputStream(socket.getOutputStream())

            val idBytes = localNodeId.value.toByteArray(Charsets.UTF_8)
            os.writeInt(0) // wireLen = 0 (HELLO)
            os.writeInt(idBytes.size)
            os.write(idBytes)
            os.flush()
            socket.close()
            println("[WifiDirect] Sent HELLO to $targetIp:$port")
            true
        } catch (e: Exception) {
            println("[WifiDirect] Could not connect to $targetIp:$port: ${e.message}")
            false
        }
    }

    private fun sendHello(targetIp: String, port: Int) {
        connectToPeerIp(targetIp, port)
    }

    fun sendBundle(bundle: DtnMessage, targetIp: String): Boolean {
        return try {
            val wire = DtnWireCodec.encode(bundle)
            val socket = Socket()
            socket.connect(InetSocketAddress(targetIp, listenPort), 3000)
            val os = DataOutputStream(socket.getOutputStream())

            val idBytes = localNodeId.value.toByteArray(Charsets.UTF_8)
            os.writeInt(wire.size)
            os.write(wire)
            os.writeInt(idBytes.size)
            os.write(idBytes)
            os.flush()
            socket.close()
            true
        } catch (e: Exception) {
            println("[WifiDirect] Failed to send bundle to $targetIp: ${e.message}")
            false
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
        delay(2000)
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
            delay(10_000L)
        }
    }

    private suspend fun subnetSweepLoop() {
        delay(3000)
        while (scope.isActive) {
            try {
                sweepLocalSubnet()
            } catch (_: Exception) {}
            delay(20_000L)
        }
    }

    fun triggerSubnetSweep() {
        scope.launch {
            sweepLocalSubnet(fullSweep = true)
        }
    }

    private suspend fun sweepLocalSubnet(fullSweep: Boolean = false) {
        if (_isSweeping.value) return
        _isSweeping.value = true
        try {
            val targets = mutableSetOf<String>()

            // Always check standard gateway / hotspot IPs
            targets.add("192.168.49.1")  // Android Wi-Fi Direct Group Owner default
            targets.add("192.168.43.1")  // Android Hotspot default
            targets.add("172.20.10.1")   // iOS Hotspot default

            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return
            val localHosts = mutableSetOf<String>()

            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    val host = addr.hostAddress
                    if (host.contains(".") && !host.startsWith("127.")) {
                        localHosts.add(host)
                        val subnetPrefix = host.substringBeforeLast(".")
                        targets.add("$subnetPrefix.1")
                        targets.add("$subnetPrefix.2")

                        if (fullSweep) {
                            for (i in 1..254) {
                                targets.add("$subnetPrefix.$i")
                            }
                        } else {
                            // Quick scan common DHCP and hotspot IPs
                            for (i in 3..30) targets.add("$subnetPrefix.$i")
                            for (i in 100..130) targets.add("$subnetPrefix.$i")
                        }
                    }
                }
            }

            targets.removeAll(localHosts)

            // Concurrently probe targets in chunks of 32
            val targetList = targets.toList()
            targetList.chunked(32).forEach { chunk ->
                val jobs = chunk.map { ip ->
                    scope.async {
                        probePort(ip, listenPort)
                    }
                }
                jobs.awaitAll()
            }
        } finally {
            _isSweeping.value = false
        }
    }

    private fun probePort(ip: String, port: Int) {
        val now = System.currentTimeMillis()
        val last = lastProbedIp[ip] ?: 0L
        if (now - last < 15_000L) return
        lastProbedIp[ip] = now

        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), 250)
            socket.close()
            // Port 9734 open! Send HELLO
            connectToPeerIp(ip, port)
        } catch (_: Exception) {}
    }
}
