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

    private val _discoverMode = MutableStateFlow(true)
    val discoverMode: StateFlow<Boolean> = _discoverMode.asStateFlow()

    private val lastAnnounceSentTo = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun setDiscoverMode(enabled: Boolean) {
        _discoverMode.value = enabled
        if (enabled) {
            broadcastAnnounce()
            lifecycleLog.logConsole("Orchestrator", "Discover Mode enabled: broadcasting presence to mesh")
        } else {
            lifecycleLog.logConsole("Orchestrator", "Discover Mode disabled: stealth mode active (unsolicited announcements halted)")
        }
    }

    fun start() {
        transportManager.onRelayConnected = {
            if (_discoverMode.value) broadcastAnnounce()
        }
        transportManager.onPeerDiscovered = { peerId ->
            if (_discoverMode.value) {
                scope.launch {
                    sendAnnounceTo(NodeId(peerId))
                }
            }
        }
        transportManager.startAll()
        scope.launch {
            flushLoop()
        }
        scope.launch {
            delay(1000)
            if (_discoverMode.value) broadcastAnnounce()
            periodicAnnounceLoop()
        }
        lifecycleLog.logConsole("Orchestrator", "Desktop Orchestrator started for node ${identity.nodeId}")
    }

    fun stop() {
        transportManager.stopAll()
    }

    fun connectToWifiPeer(ip: String): Boolean = transportManager.connectToWifiPeer(ip)

    fun triggerSubnetSweep() = transportManager.triggerSubnetSweep()

    fun getLocalIpAddresses(): List<String> = transportManager.getLocalIpAddresses()

    fun restartBle() = transportManager.restartBle()

    fun setStrategy(type: StrategyType) {
        _activeStrategy.value = type
        strategySelector.switchTo(type)
        lifecycleLog.logConsole("Orchestrator", "Active routing strategy changed to $type")
    }

    fun sendTextMessage(destination: NodeId, text: String): String {
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // 1. Encrypt text payload
        val plainBytes = text.toByteArray(Charsets.UTF_8)
        val payloadBytes: ByteArray
        val flags: Int

        if (destination == NodeId.BROADCAST) {
            payloadBytes = plainBytes
            flags = (DtnCrypto.SUITE_PLAIN and 0x07) or (1 shl 6)
        } else {
            // Lookup recipient contact X25519 public key
            val contact = database.contacts.value.firstOrNull { it.nodeId == destination.value }
            val dmPlain = com.dtn.mesh.routing.AlgorithmCode.prefix(strategySelector.activeType, plainBytes)
            if (contact?.xPub != null) {
                payloadBytes = EnvelopeService.sealBytes(
                    plaintext = dmPlain,
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
                flags = (DtnCrypto.SUITE_DM and 0x07) or (1 shl 6)
            } else {
                // If keys not yet pinned, transmit plaintext with prompt and request announce
                payloadBytes = dmPlain
                flags = (DtnCrypto.SUITE_PLAIN and 0x07) or (1 shl 6)
                sendAnnounceTo(destination, wantsReply = true)
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
            // Mutual Announce: If peer requested a reply, or is direct, or we haven't announced to peer recently
            val wantsReply = (payload.flags and Announce.FLAG_WANTS_REPLY) != 0
            val isDirect = bundle.destinationNodeId == identity.nodeId
            val peerStr = bundle.originNodeId.value
            val lastSent = lastAnnounceSentTo[peerStr] ?: 0L
            val now = System.currentTimeMillis()
            if (wantsReply || isDirect || (now - lastSent > 60_000L)) {
                sendAnnounceTo(bundle.originNodeId)
            }
        }
    }

    private fun handleAck(bundle: DtnMessage) {
        val payload = bundle.payloadBytes
        if (payload.size >= 16) {
            val count = payload.size / 16
            for (i in 0 until count) {
                val bb = java.nio.ByteBuffer.wrap(payload, i * 16, 16)
                val targetMsgId = UUID(bb.long, bb.long).toString()
                database.updateChatStatus(targetMsgId, "DELIVERED")
                database.removeBufferedMessage(targetMsgId)
                lifecycleLog.logEvent(LifecycleEvent.DELIVERED, targetMsgId, bundle.originNodeId.value, identity.nodeId.value, "Receipt ✓✓")
            }
        } else {
            val targetMsgId = String(payload, Charsets.UTF_8).trim()
            database.updateChatStatus(targetMsgId, "DELIVERED")
            database.removeBufferedMessage(targetMsgId)
            lifecycleLog.logEvent(LifecycleEvent.DELIVERED, targetMsgId, bundle.originNodeId.value, identity.nodeId.value, "Receipt ✓✓")
        }
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
            val suite = bundle.flags and 0x07
            val text: String

            if (suite == DtnCrypto.SUITE_DM) {
                if (contact?.xPub != null) {
                    text = try {
                        val raw = EnvelopeService.openBytes(
                            msg = bundle,
                            recipientPriv = identity.xPriv,
                            senderPub = contact.xPub,
                        )
                        if (raw != null) {
                            val (algo, cleanBytes) = com.dtn.mesh.routing.AlgorithmCode.split(raw)
                            String(cleanBytes, Charsets.UTF_8)
                        } else {
                            "[Encrypted DM - could not decrypt]"
                        }
                    } catch (e: Exception) {
                        "[Encrypted DM - could not decrypt: ${e.message}]"
                    }
                } else {
                    text = "[Encrypted DM - waiting for peer public key]"
                    sendAnnounceTo(bundle.originNodeId, wantsReply = true)
                }
            } else {
                val (algo, cleanBytes) = com.dtn.mesh.routing.AlgorithmCode.split(bundle.payloadBytes)
                text = String(cleanBytes, Charsets.UTF_8)
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
        val u = try {
            UUID.fromString(originalMsgId)
        } catch (_: Exception) {
            UUID.nameUUIDFromBytes(originalMsgId.toByteArray(Charsets.UTF_8))
        }
        val bb = java.nio.ByteBuffer.allocate(16)
        bb.putLong(u.mostSignificantBits)
        bb.putLong(u.leastSignificantBits)
        val ackBundle = DtnMessage(
            id = UUID.randomUUID().toString(),
            originNodeId = identity.nodeId,
            destinationNodeId = origin,
            payloadBytes = bb.array(),
            createdAtMs = System.currentTimeMillis(),
            ttlMs = 300_000L,
            messageType = DtnMessageType.ROUTING_ACK
        )
        transportManager.transmitBundle(ackBundle)
    }

    fun sendAnnounceTo(destination: NodeId, wantsReply: Boolean = false) {
        val now = System.currentTimeMillis()
        lastAnnounceSentTo[destination.value] = now
        val computerName = System.getenv("COMPUTERNAME") ?: "Desktop Station"
        val payload = Announce.build(
            nodeNum = identity.nodeNum32,
            xPub = identity.xPub,
            edPub = identity.edPub,
            edSeed = identity.edSeed,
            name = computerName,
            issuedAtSec = (now / 1000).toInt(),
            flags = if (wantsReply) Announce.FLAG_WANTS_REPLY else 0,
        )
        val bundle = DtnMessage(
            id = UUID.randomUUID().toString(),
            originNodeId = identity.nodeId,
            destinationNodeId = destination,
            payloadBytes = payload,
            createdAtMs = now,
            ttlMs = 1800_000L,
            messageType = DtnMessageType.ANNOUNCE
        )
        transportManager.transmitBundle(bundle)
        lifecycleLog.logConsole("Orchestrator", "Sent direct ANNOUNCE to ${destination.value.takeLast(8)}")
    }

    fun flushQueueNow() {
        scope.launch {
            val buffered = database.bufferedMessages.value
            for (msg in buffered) {
                val ok = transportManager.transmitBundle(msg)
                if (ok) {
                    database.updateChatStatus(msg.id, "SENT")
                }
            }
        }
    }

    fun flushBundleNow(bundle: DtnMessage) {
        scope.launch {
            val ok = transportManager.transmitBundle(bundle)
            if (ok) {
                database.updateChatStatus(bundle.id, "SENT")
                lifecycleLog.logEvent(LifecycleEvent.FORWARDED, bundle.id, bundle.originNodeId.value, bundle.destinationNodeId.value, "Transmitted manually via transport")
            }
        }
    }

    fun clearExpiredQueue() {
        val now = System.currentTimeMillis()
        val expired = database.bufferedMessages.value.filter { (now - it.createdAtMs) > it.ttlMs }
        for (msg in expired) {
            database.removeBufferedMessage(msg.id)
            lifecycleLog.logEvent(LifecycleEvent.EXPIRED, msg.id, msg.originNodeId.value, msg.destinationNodeId.value, "TTL expired in queue")
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
            delay(45_000L) // Scan & announce every 45s when discover mode is active
            if (_discoverMode.value) {
                broadcastAnnounce()
            }
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

    fun buildMyInviteLink(): String {
        val host = "dtnmesh.relaynetwork.workers.dev"
        val code = com.dtn.mesh.crypto.IdentityQr.build(
            identity.nodeNum32,
            identity.xPub,
            identity.edPub,
            System.getenv("COMPUTERNAME") ?: "Desktop Station"
        )
        return "https://$host/c#$code"
    }
}
