package com.dtn.mesh.transport

import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.receiver.DtnWireCodec
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Dual internet relay adapter for Desktop: HiveMQ MQTT + Cloudflare Worker WebSocket.
 * Strictly adheres to Android DTN Relay v1 topic structure and framing.
 */
class DesktopInternetTransportAdapter(
    private val localNodeId: NodeId,
    private val mqttHost: String = "broker.hivemq.com",
    private val mqttPort: Int = 8883,
    private val workerWsUri: String = "wss://dtnmesh.vrishankrainaphotos1.workers.dev/ws",
    private val onMessageReceived: (DtnMessage, String) -> Unit,
    private val onPeerStatusChanged: (String, Boolean, String) -> Unit,
    var onRelayConnected: (() -> Unit)? = null,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var mqttClient: Mqtt3AsyncClient? = null
    private var wsClient: WebSocketClient? = null

    private val localTag = localNodeId.value.removePrefix("!").lowercase()
    private val computerName = System.getenv("COMPUTERNAME") ?: "Desktop Station"

    fun start() {
        scope.launch {
            connectMqtt()
        }
        scope.launch {
            connectWebSocket()
        }
        scope.launch {
            heartbeatLoop()
        }
    }

    fun stop() {
        publishPresence(online = false)
        runCatching { mqttClient?.disconnect() }
        runCatching { wsClient?.close() }
        _isConnected.value = false
    }

    private fun connectMqtt() {
        try {
            val willTopic = "dtn/v1/presence/$localTag"
            val willEpoch = System.currentTimeMillis() / 1000L
            val willPayload = "off:$willEpoch".toByteArray(Charsets.US_ASCII)

            val client = MqttClient.builder()
                .useMqttVersion3()
                .identifier("dtn-desktop-$localTag")
                .serverHost(mqttHost)
                .serverPort(mqttPort)
                .useSslWithDefaultConfig()
                .buildAsync()

            client.connectWith()
                .willPublish()
                    .topic(willTopic)
                    .payload(willPayload)
                    .retain(true)
                    .applyWillPublish()
                .send()
                .whenComplete { _, throwable ->
                    if (throwable == null) {
                        mqttClient = client
                        _isConnected.value = true
                        println("[MqttRelay] Connected to $mqttHost:$mqttPort as !$localTag")

                        // 1. Subscribe to node DM topic
                        client.subscribeWith()
                            .topicFilter("dtn/v1/node/$localTag")
                            .callback { pub -> handleMqttPayload(pub.payloadAsBytes) }
                            .send()

                        // 2. Subscribe to global broadcasts
                        client.subscribeWith()
                            .topicFilter("dtn/v1/bcast")
                            .callback { pub -> handleMqttPayload(pub.payloadAsBytes) }
                            .send()

                        // 3. Subscribe to all peer presence updates
                        client.subscribeWith()
                            .topicFilter("dtn/v1/presence/+")
                            .callback { pub -> handlePresenceUpdate(pub.topic.toString(), pub.payloadAsBytes) }
                            .send()

                        // 4. Publish online presence immediately
                        publishPresence(online = true)

                        // 5. Trigger immediate initial announce broadcast
                        onRelayConnected?.invoke()
                    } else {
                        println("[MqttRelay] Connect failed: ${throwable.message}")
                    }
                }
        } catch (e: Exception) {
            println("[MqttRelay] Exception: ${e.message}")
        }
    }

    private fun publishPresence(online: Boolean) {
        val client = mqttClient ?: return
        if (!client.state.isConnected) return
        val epochSec = System.currentTimeMillis() / 1000L
        val b64Name = Base64.getEncoder().withoutPadding().encodeToString(computerName.toByteArray(Charsets.UTF_8))
        val payload = if (online) {
            "on:$epochSec:$b64Name".toByteArray(Charsets.US_ASCII)
        } else {
            "off:$epochSec".toByteArray(Charsets.US_ASCII)
        }
        val topic = "dtn/v1/presence/$localTag"
        client.publishWith()
            .topic(topic)
            .payload(payload)
            .retain(true)
            .send()
    }

    private suspend fun heartbeatLoop() {
        while (scope.isActive) {
            delay(30_000L)
            if (_isConnected.value) {
                publishPresence(online = true)
            }
        }
    }

    private fun handlePresenceUpdate(topic: String, payload: ByteArray) {
        try {
            val peerHex = topic.substringAfterLast("/").lowercase()
            if (peerHex == localTag || peerHex.length != 8) return

            val text = String(payload, Charsets.US_ASCII)
            val parts = text.split(":", limit = 3)
            val isOnline = parts.getOrNull(0) == "on"

            val peerName = if (parts.size >= 3 && parts[2].isNotBlank()) {
                runCatching {
                    String(Base64.getDecoder().decode(parts[2]), Charsets.UTF_8)
                }.getOrNull()
            } else null

            val peerId = "!$peerHex"
            onPeerStatusChanged(peerId, isOnline, "Internet Relay" + (peerName?.let { " ($it)" } ?: ""))

            if (isOnline) {
                // If a peer just came online on the relay, broadcast our announce so they discover us immediately
                onRelayConnected?.invoke()
            }
        } catch (_: Exception) {}
    }

    private fun handleMqttPayload(raw: ByteArray) {
        try {
            // Android framing: [D7 01][4B publisherNodeNum][wire bytes...]
            val wireBytes = if (raw.size >= 6 && raw[0] == 0xD7.toByte() && raw[1] == 0x01.toByte()) {
                raw.copyOfRange(6, raw.size)
            } else {
                raw
            }
            val bundle = DtnWireCodec.decode(wireBytes, rssi = -50, snr = 10f) ?: return
            if (bundle.originNodeId == localNodeId) return // Drop our own echo
            onMessageReceived(bundle, "Internet Relay")
        } catch (_: Exception) {}
    }

    private fun connectWebSocket() {
        try {
            val uri = URI("$workerWsUri?nodeId=${localNodeId.value}")
            wsClient = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    println("[WsRelay] Connected to Cloudflare Worker WebSocket")
                    _isConnected.value = true
                    onRelayConnected?.invoke()
                }

                override fun onMessage(bytes: ByteBuffer?) {
                    bytes?.let {
                        val arr = ByteArray(it.remaining())
                        it.get(arr)
                        handleMqttPayload(arr)
                    }
                }

                override fun onMessage(message: String?) {}
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    println("[WsRelay] Closed ($code): $reason")
                }
                override fun onError(ex: Exception?) {
                    println("[WsRelay] Error: ${ex?.message}")
                }
            }
            wsClient?.connect()
        } catch (_: Exception) {}
    }

    fun sendBundle(bundle: DtnMessage): Boolean {
        var sent = false
        val wire = DtnWireCodec.encode(bundle)
        // Wrap with [0xD7, 0x01][4B localNodeNum][wire]
        val frame = ByteBuffer.allocate(6 + wire.size).order(ByteOrder.BIG_ENDIAN)
        frame.put(0xD7.toByte())
        frame.put(0x01.toByte())
        frame.putInt(localNodeId.toNodeNum32())
        frame.put(wire)
        val frameBytes = frame.array()

        // 1. MQTT publish
        val topic = if (bundle.destinationNodeId == NodeId.BROADCAST) {
            "dtn/v1/bcast"
        } else {
            "dtn/v1/node/${bundle.destinationNodeId.value.removePrefix("!").lowercase()}"
        }

        mqttClient?.let {
            if (it.state.isConnected) {
                it.publishWith()
                    .topic(topic)
                    .payload(frameBytes)
                    .send()
                sent = true
            }
        }

        // 2. WebSocket publish
        wsClient?.let {
            if (it.isOpen) {
                it.send(frameBytes)
                sent = true
            }
        }

        return sent
    }
}
