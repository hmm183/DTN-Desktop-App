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

/**
 * Dual internet relay adapter for Desktop: HiveMQ MQTT + Cloudflare Worker WebSocket.
 */
class DesktopInternetTransportAdapter(
    private val localNodeId: NodeId,
    private val mqttHost: String = "broker.hivemq.com",
    private val mqttPort: Int = 8883,
    private val workerWsUri: String = "wss://dtnmesh.vrishankrainaphotos1.workers.dev/ws",
    private val onMessageReceived: (DtnMessage, String) -> Unit,
    private val onPeerStatusChanged: (String, Boolean, String) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var mqttClient: Mqtt3AsyncClient? = null
    private var wsClient: WebSocketClient? = null

    private val localTag = localNodeId.value.removePrefix("!")

    fun start() {
        scope.launch {
            connectMqtt()
        }
        scope.launch {
            connectWebSocket()
        }
    }

    fun stop() {
        runCatching { mqttClient?.disconnect() }
        runCatching { wsClient?.close() }
        _isConnected.value = false
    }

    private fun connectMqtt() {
        try {
            val client = MqttClient.builder()
                .useMqttVersion3()
                .identifier("dtn-desktop-$localTag")
                .serverHost(mqttHost)
                .serverPort(mqttPort)
                .useSslWithDefaultConfig()
                .buildAsync()

            client.connectWith()
                .send()
                .whenComplete { _, throwable ->
                    if (throwable == null) {
                        mqttClient = client
                        _isConnected.value = true
                        println("[MqttRelay] Connected to $mqttHost:$mqttPort")

                        // Subscribe to DM topic and broadcast topic
                        client.subscribeWith()
                            .topicFilter("dtn/v1/node/$localTag")
                            .callback { pub -> handleMqttPayload(pub.payloadAsBytes) }
                            .send()

                        client.subscribeWith()
                            .topicFilter("dtn/v1/bcast")
                            .callback { pub -> handleMqttPayload(pub.payloadAsBytes) }
                            .send()

                        // Publish presence
                        val presenceTopic = "dtn/v1/presence/$localTag"
                        client.publishWith()
                            .topic(presenceTopic)
                            .payload("on:${System.currentTimeMillis()}".toByteArray())
                            .retain(true)
                            .send()
                    } else {
                        println("[MqttRelay] Connect failed: ${throwable.message}")
                    }
                }
        } catch (e: Exception) {
            println("[MqttRelay] Exception: ${e.message}")
        }
    }

    private fun handleMqttPayload(raw: ByteArray) {
        try {
            // Check [D7 01][4B publisher][wire] framing
            val wireBytes = if (raw.size >= 6 && raw[0] == 0xD7.toByte() && raw[1] == 0x01.toByte()) {
                raw.copyOfRange(6, raw.size)
            } else {
                raw
            }
            val bundle = DtnWireCodec.decode(wireBytes, rssi = -50, snr = 10f) ?: return
            onMessageReceived(bundle, "MQTT_RELAY")
        } catch (_: Exception) {}
    }

    private fun connectWebSocket() {
        try {
            val uri = URI("$workerWsUri?nodeId=${localNodeId.value}")
            wsClient = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    println("[WsRelay] Connected to Cloudflare Worker WebSocket")
                    _isConnected.value = true
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
        // Wrap with [D7 01][4B localNodeNum][wire]
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
