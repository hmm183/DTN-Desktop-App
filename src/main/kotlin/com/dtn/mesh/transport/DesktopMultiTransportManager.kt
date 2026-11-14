package com.dtn.mesh.transport

import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class PeerEndpoint(
    val nodeId: String,
    val isOnline: Boolean,
    val linkType: String,
    val lastSeenMs: Long = System.currentTimeMillis()
)

class DesktopMultiTransportManager(
    val localNodeId: NodeId,
    private val onInboundBundle: (DtnMessage, String) -> Unit,
) {
    val usbSerial = UsbSerialTransportAdapter(
        localNodeId = localNodeId,
        onMessageReceived = { msg, via -> onInboundBundle(msg, via) },
        onPeerStatusChanged = { id, online, link -> updatePeer(id, online, link) }
    )

    val tcpHub = TcpLoRaHubTransportAdapter(
        localNodeId = localNodeId,
        onMessageReceived = { msg, via -> onInboundBundle(msg, via) },
        onPeerStatusChanged = { id, online, link -> updatePeer(id, online, link) }
    )

    val ble = WindowsBleTransportAdapter(
        localNodeId = localNodeId,
        onMessageReceived = { msg, via -> onInboundBundle(msg, via) },
        onPeerStatusChanged = { id, online, link -> updatePeer(id, online, link) }
    )

    val wifiDirect = WindowsWifiDirectTransportAdapter(
        localNodeId = localNodeId,
        onMessageReceived = { msg, via -> onInboundBundle(msg, via) },
        onPeerStatusChanged = { id, online, link -> updatePeer(id, online, link) }
    )

    var onRelayConnected: (() -> Unit)? = null
    var onPeerDiscovered: ((String) -> Unit)? = null

    val internet = DesktopInternetTransportAdapter(
        localNodeId = localNodeId,
        onMessageReceived = { msg, via -> onInboundBundle(msg, via) },
        onPeerStatusChanged = { id, online, link -> updatePeer(id, online, link) },
        onRelayConnected = { onRelayConnected?.invoke() }
    )

    private val _onlinePeers = MutableStateFlow<Map<String, PeerEndpoint>>(emptyMap())
    val onlinePeers: StateFlow<Map<String, PeerEndpoint>> = _onlinePeers.asStateFlow()

    fun startAll() {
        usbSerial.start()
        tcpHub.start()
        ble.start()
        wifiDirect.start()
        internet.start()
        println("[MultiTransport] All 5 desktop transports started.")
    }

    fun stopAll() {
        usbSerial.stop()
        tcpHub.stop()
        ble.stop()
        wifiDirect.stop()
        internet.stop()
        println("[MultiTransport] All 5 desktop transports stopped.")
    }

    fun connectToWifiPeer(ip: String): Boolean = wifiDirect.connectToPeerIp(ip)

    fun triggerSubnetSweep() = wifiDirect.triggerSubnetSweep()

    fun getLocalIpAddresses(): List<String> = wifiDirect.getLocalIpAddresses()

    fun restartBle() {
        ble.stop()
        ble.start()
    }

    private fun updatePeer(nodeId: String, online: Boolean, linkType: String) {
        val current = _onlinePeers.value.toMutableMap()
        val isNew = !current.containsKey(nodeId)
        if (online) {
            current[nodeId] = PeerEndpoint(nodeId, true, linkType, System.currentTimeMillis())
            if (isNew) {
                onPeerDiscovered?.invoke(nodeId)
            }
        } else {
            current.remove(nodeId)
        }
        _onlinePeers.value = current
    }

    /**
     * Sends bundle out across all available transport paths.
     * Broadcast bundles are fanned out to all local peers (Wi-Fi, BLE, LoRa, Internet).
     * Unicast bundles prioritize low-latency local paths (Wi-Fi Direct, BLE) with Internet fallback.
     */
    fun transmitBundle(bundle: DtnMessage): Boolean {
        if (bundle.destinationNodeId == NodeId.BROADCAST) {
            var sentAny = false

            // Fan out to all connected Wi-Fi peers
            val wifiIps = wifiDirect.connectedPeers.value.values.toSet()
            for (ip in wifiIps) {
                if (wifiDirect.sendBundle(bundle, ip)) sentAny = true
            }

            // Fan out to all discovered BLE peers
            val blePeers = ble.discoveredPeers.value.keys.toSet()
            for (peerId in blePeers) {
                if (ble.sendBundle(bundle, peerId)) sentAny = true
            }

            // USB Serial LoRa
            if (usbSerial.isConnected.value) {
                if (usbSerial.sendBundle(bundle)) sentAny = true
            }

            // Wi-Fi SoftAP LoRa
            if (tcpHub.isConnected.value) {
                if (tcpHub.sendBundle(bundle)) sentAny = true
            }

            // Internet Relay (Broadcast)
            if (internet.isConnected.value) {
                if (internet.sendBundle(bundle)) sentAny = true
            }

            return sentAny
        }

        // Unicast Bundle
        val targetId = bundle.destinationNodeId.value
        var sentOk = false

        // 1. Wi-Fi Direct (if peer reachable via P2P / LAN IP)
        val peerIp = wifiDirect.connectedPeers.value[targetId]
        if (peerIp != null) {
            val ok = wifiDirect.sendBundle(bundle, peerIp)
            if (ok) sentOk = true
        }

        // 2. BLE (if peer nearby over BLE)
        if (ble.discoveredPeers.value.containsKey(targetId)) {
            val ok = ble.sendBundle(bundle, targetId)
            if (ok) sentOk = true
        }

        // 3. USB Serial LoRa
        if (!sentOk && usbSerial.isConnected.value) {
            val ok = usbSerial.sendBundle(bundle)
            if (ok) sentOk = true
        }

        // 4. Wi-Fi SoftAP LoRa
        if (!sentOk && tcpHub.isConnected.value) {
            val ok = tcpHub.sendBundle(bundle)
            if (ok) sentOk = true
        }

        // 5. Internet Relay
        if (internet.isConnected.value) {
            val ok = internet.sendBundle(bundle)
            if (ok) sentOk = true
        }

        return sentOk
    }
}
