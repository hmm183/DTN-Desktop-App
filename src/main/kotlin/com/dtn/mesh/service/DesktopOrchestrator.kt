package com.dtn.mesh.service

import com.dtn.mesh.crypto.Announce
import com.dtn.mesh.crypto.DtnCrypto
import com.dtn.mesh.crypto.DtnIdentity
import com.dtn.mesh.crypto.EnvelopeService
import com.dtn.mesh.database.ChatMessage
import com.dtn.mesh.database.Contact
import com.dtn.mesh.database.DesktopDatabase
import com.dtn.mesh.learning.DoubleQLearningEngine
import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.routing.StrategySelector
import com.dtn.mesh.routing.StrategySelector.StrategyType
import com.dtn.mesh.transport.DesktopMultiTransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class DesktopOrchestrator(
    val identity: DtnIdentity,
    val database: DesktopDatabase,
    val lifecycleLog: MessageLifecycleLog,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val strategySelector = StrategySelector().apply { setSelfId(identity.nodeId.value) }
    val qEngine = DoubleQLearningEngine()

    val transportManager = DesktopMultiTransportManager(
        localNodeId = identity.nodeId,
        onInboundBundle = { bundle, via -> onInboundBundle(bundle, via) }
    )

    private val _activeStrategy = MutableStateFlow(StrategyType.EPIDEMIC)
    val activeStrategy: StateFlow<StrategyType> = _activeStrategy.asStateFlow()

    fun start() {
        transportManager.onRelayConnected = {
            broadcastAnnounce()
        }
        transportManager.startAll()
        scope.launch {
            flushLoop()
        }
        scope.launch {
            delay(1000)
            broadcastAnnounce()
            periodicAnnounceLoop()
        }
        lifecycleLog.logConsole("Orchestrator", "Desktop Orchestrator started for node ${identity.nodeId}")
    }

    fun stop() {
        transportManager.stopAll()
    }

    fun setStrategy(type: StrategyType) {
        _activeStrategy.value = type
        strategySelector.switchTo(type)
        lifecycleLog.logConsole("Orchestrator", "Active routing strategy changed to $type")
    }

    fun sendTextMessage(destination: NodeId, text: String): String {
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // 1. Encrypt text payload
        val payloadBytes: ByteArray
        val flags: Int

        if (destination == NodeId.BROADCAST) {
            payloadBytes = text.toByteArray(Charsets.UTF_8)
            flags = 0 // Plaintext
        } else {
            // Lookup recipient contact X25519 public key
            val contact = database.contacts.value.firstOrNull { it.nodeId == destination.value }
            if (contact?.xPub != null) {
                payloadBytes = EnvelopeService.sealBytes(
                    plaintext = text.toByteArray(Charsets.UTF_8),
                    messageId = msgId,
                    originNodeId = identity.nodeId,
                    destinationNodeId = destination,
                    channel = 0,
                    fragIndex = 0,
                    fragTotal = 1,
                    suite = DtnCrypto.SUITE_DM,
                    senderPriv = identity.xPriv,
                    recipientPub = contact.xPub,
                )
                flags = DtnCrypto.SUITE_DM
            } else {
                // If keys not yet pinned, transmit plaintext with prompt
                payloadBytes = text.toByteArray(Charsets.UTF_8)
                flags = 0
            }
        }

        // 2. Insert into Chat Database
        val convKey = if (destination == NodeId.BROADCAST) "^all" else destination.value
        database.insertChatMessage(
            ChatMessage(
                id = msgId,
                convKey = convKey,
                peerNodeId = if (destination == NodeId.BROADCAST) null else destination.value,
                channel = 0,
                direction = "OUT",
                text = text,
                status = "BUFFERED",
                createdAtMs = now,
            )
        )

        // 3. Build DTN bundle
        val bundle = DtnMessage(
            id = msgId,
            originNodeId = identity.nodeId,
            destinationNodeId = destination,
            payloadBytes = payloadBytes,
            createdAtMs = now,
            ttlMs = 4 * 3600_000L,
            flags = flags,
            messageType = DtnMessageType.DATA
        )

        // 4. Save to store-carry-forward buffer
        database.storeBufferedMessage(bundle)
        lifecycleLog.logEvent(LifecycleEvent.CREATED, msgId, identity.nodeId.value, destination.value, "User sent")

        // 5. Immediate opportunistic send
        scope.launch {
            val sent = transportManager.transmitBundle(bundle)
            if (sent) {
                database.updateChatStatus(msgId, "SENT")
                lifecycleLog.logEvent(LifecycleEvent.FORWARDING, msgId, identity.nodeId.value, destination.value, "Transmitted via transport")
            }
        }

        return msgId
    }

    private fun onInboundBundle(bundle: DtnMessage, via: String) {
        // Prevent duplicate processing
        if (database.isSeen(bundle.id)) return
        database.markSeen(bundle.id)

        lifecycleLog.logEvent(LifecycleEvent.BUFFERED, bundle.id, bundle.originNodeId.value, bundle.destinationNodeId.value, "via $via")

        when (bundle.messageType) {
            DtnMessageType.ANNOUNCE -> {
                handleAnnounce(bundle)
            }
            DtnMessageType.ROUTING_ACK -> {
                handleAck(bundle)
            }
            DtnMessageType.DATA -> {
                handleDataBundle(bundle, via)
            }
            else -> {}
        }
    }

    private fun handleAnnounce(bundle: DtnMessage) {
        val payload = Announce.parseAndVerify(bundle.payloadBytes)
        if (payload != null) {
            println("[Orchestrator] Verified signed announce from ${bundle.originNodeId} (name: ${payload.name})")
            database.upsertContact(
                Contact(
                    nodeId = bundle.originNodeId.value,
                    xPub = payload.xPub,
                    edPub = payload.edPub,
                    announceName = payload.name,
                    keyVerified = true,
                    keyFirstSeenMs = System.currentTimeMillis()
                )
            )
        }
    }

    private fun handleAck(bundle: DtnMessage) {
        // Receipt received!
        val targetMsgId = String(bundle.payloadBytes, Charsets.UTF_8).trim()
        database.updateChatStatus(targetMsgId, "DELIVERED")
        database.removeBufferedMessage(targetMsgId)
        lifecycleLog.logEvent(LifecycleEvent.DELIVERED, targetMsgId, bundle.originNodeId.value, identity.nodeId.value, "Receipt ✓✓")
    }

    private fun handleDataBundle(bundle: DtnMessage, via: String) {
        val isForMe = bundle.destinationNodeId == identity.nodeId
        val isBroadcast = bundle.destinationNodeId == NodeId.BROADCAST

        if (isForMe || isBroadcast) {
            val contact = database.contacts.value.firstOrNull { it.nodeId == bundle.originNodeId.value }

            // Auto-discover sender if not already known
            if (contact == null && !isBroadcast) {
                database.upsertContact(
                    Contact(
                        nodeId = bundle.originNodeId.value,
                        announceName = "Peer ${bundle.originNodeId.value.takeLast(4)}",
                        keyVerified = false,
                        keyFirstSeenMs = System.currentTimeMillis()
                    )
                )
            }

            // Decrypt payload
            val text: String

            if (bundle.flags == DtnCrypto.SUITE_DM && contact?.xPub != null) {
                text = try {
                    val raw = EnvelopeService.openBytes(
                        sealed = bundle.payloadBytes,
                        messageId = bundle.id,
                        originNodeId = bundle.originNodeId,
                        destinationNodeId = bundle.destinationNodeId,
                        channel = 0,
                        fragIndex = 0,
                        fragTotal = 1,
                        suite = DtnCrypto.SUITE_DM,
                        recipientPriv = identity.xPriv,
                        senderPub = contact.xPub,
                    )
                    raw?.let { String(it, Charsets.UTF_8) } ?: "[Encrypted message - could not decrypt]"
                } catch (e: Exception) {
                    "[Encrypted message - could not decrypt]"
                }
            } else {
                text = String(bundle.payloadBytes, Charsets.UTF_8)
            }

            // Save to chat history
            val convKey = if (isBroadcast) "^all" else bundle.originNodeId.value
            database.insertChatMessage(
                ChatMessage(
                    id = bundle.id,
                    convKey = convKey,
                    peerNodeId = bundle.originNodeId.value,
                    channel = bundle.channel,
                    direction = "IN",
                    text = text,
                    status = "RECEIVED",
                    createdAtMs = bundle.createdAtMs,
                )
            )

            // If it was addressed specifically to me, send back an end-to-end receipt (ROUTING_ACK)
            if (isForMe) {
                sendReceipt(bundle.originNodeId, bundle.id)
            }
        }

        // If not for me or broadcast: store in buffer for onward carrier forwarding (Store-Carry-Forward)
        if (!isForMe) {
            database.storeBufferedMessage(bundle)
        }
    }

    private fun sendReceipt(origin: NodeId, originalMsgId: String) {
        val ackBundle = DtnMessage(
            id = UUID.randomUUID().toString(),
            originNodeId = identity.nodeId,
            destinationNodeId = origin,
            payloadBytes = originalMsgId.toByteArray(Charsets.UTF_8),
            createdAtMs = System.currentTimeMillis(),
            ttlMs = 3600_000L,
            messageType = DtnMessageType.ROUTING_ACK
        )
        transportManager.transmitBundle(ackBundle)
    }

    fun flushQueueNow() {
        scope.launch {
            val buffered = database.bufferedMessages.value
            for (msg in buffered) {
                transportManager.transmitBundle(msg)
            }
        }
    }

    private suspend fun flushLoop() {
        while (scope.isActive) {
            delay(5000)
            val buffered = database.bufferedMessages.value
            if (buffered.isNotEmpty()) {
                val strategy = strategySelector.active
                for (msg in buffered) {
                    // Try opportunistic forwarding
                    transportManager.transmitBundle(msg)
                }
            }
        }
    }

    private suspend fun periodicAnnounceLoop() {
        while (scope.isActive) {
            broadcastAnnounce()
            delay(300_000L) // Every 5 minutes
        }
    }

    fun broadcastAnnounce() {
        val computerName = System.getenv("COMPUTERNAME") ?: "Desktop Station"
        val payload = Announce.build(
            nodeNum = identity.nodeNum32,
            xPub = identity.xPub,
            edPub = identity.edPub,
            edSeed = identity.edSeed,
            name = computerName,
            issuedAtSec = (System.currentTimeMillis() / 1000).toInt(),
        )
        val bundle = DtnMessage(
            id = UUID.randomUUID().toString(),
            originNodeId = identity.nodeId,
            destinationNodeId = NodeId.BROADCAST,
            payloadBytes = payload,
            createdAtMs = System.currentTimeMillis(),
            ttlMs = 1800_000L,
            messageType = DtnMessageType.ANNOUNCE
        )
        transportManager.transmitBundle(bundle)
    }
}
