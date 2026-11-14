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

    val internet = DesktopInternetTransportAdapter(
        localNodeId = localNodeId,
        onMessageReceived = { msg, via -> onInboundBundle(msg, via) },
        onPeerStatusChanged = { id, online, link -> updatePeer(id, online, link) }
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

    private fun updatePeer(nodeId: String, online: Boolean, linkType: String) {
        val current = _onlinePeers.value.toMutableMap()
        if (online) {
            current[nodeId] = PeerEndpoint(nodeId, true, linkType, System.currentTimeMillis())
        } else {
            current.remove(nodeId)
        }
        _onlinePeers.value = current
    }

    /**
     * Sends bundle out according to transport priority:
     * 1. USB Serial LoRa Hub (if connected)
     * 2. Wi-Fi Direct (if peer reachable via P2P IP)
     * 3. BLE (if peer nearby over BLE)
     * 4. Wi-Fi SoftAP LoRa Hub (if connected)
     * 5. Internet Relay (fallback)
     */
    fun transmitBundle(bundle: DtnMessage): Boolean {
        // 1. USB Serial LoRa
        if (usbSerial.isConnected.value) {
            val ok = usbSerial.sendBundle(bundle)
            if (ok) return true
        }

        // 2. Wi-Fi Direct
        val targetId = bundle.destinationNodeId.value
        val peerIp = wifiDirect.connectedPeers.value[targetId]
        if (peerIp != null) {
            val ok = wifiDirect.sendBundle(bundle, peerIp)
            if (ok) return true
        }

        // 3. BLE
        if (ble.discoveredPeers.value.containsKey(targetId)) {
            val ok = ble.sendBundle(bundle, targetId)
            if (ok) return true
        }

        // 4. Wi-Fi SoftAP LoRa
        if (tcpHub.isConnected.value) {
            val ok = tcpHub.sendBundle(bundle)
            if (ok) return true
        }

        // 5. Internet Relay
        if (internet.isConnected.value) {
            return internet.sendBundle(bundle)
        }

        return false
    }
}
