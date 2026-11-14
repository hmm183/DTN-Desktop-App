package com.dtn.mesh.crypto

import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.receiver.DtnWireCodec
import java.nio.ByteBuffer
import java.util.UUID

/**
 * End-to-end envelope for sealing outgoing plaintext and opening incoming ciphertext on Desktop.
 */
object EnvelopeService {

    fun sealBytes(
        plaintext: ByteArray,
        messageId: String,
        originNodeId: NodeId,
        destinationNodeId: NodeId,
        channel: Int,
        fragIndex: Int,
        fragTotal: Int,
        suite: Int,
        senderPriv: ByteArray,
        recipientPub: ByteArray?,
    ): ByteArray {
        val originNum = originNodeId.toNodeNum32()
        val destNum = destinationNodeId.toNodeNum32()
        val mid = uuid16(messageId)
        val flags = (suite and 0x07) or (1 shl 6)
        val aad = aad(messageId, originNodeId, destinationNodeId, channel, flags, fragIndex, fragTotal)

        return when (suite) {
            DtnCrypto.SUITE_PLAIN -> plaintext
            DtnCrypto.SUITE_DM -> {
                requireNotNull(recipientPub) { "Recipient public key required for DM encryption" }
                val root = DtnCrypto.x25519(senderPriv, recipientPub)
                val sealedBytes = DtnCrypto.seal(root, mid, originNum, destNum, DtnCrypto.SUITE_DM, fragIndex, aad, plaintext)
                sealedBytes ?: plaintext
            }
            else -> plaintext
        }
    }

    fun openBytes(
        msg: DtnMessage,
        recipientPriv: ByteArray,
        senderPub: ByteArray,
    ): ByteArray? {
        val suite = msg.flags and 0x07
        val mid = uuid16(msg.id)
        val originNum = msg.originNodeId.toNodeNum32()
        val destNum = msg.destinationNodeId.toNodeNum32()
        val aad = DtnWireCodec.aadHeader(msg)

        return when (suite) {
            DtnCrypto.SUITE_PLAIN -> msg.payloadBytes
            DtnCrypto.SUITE_DM -> {
                val root = DtnCrypto.x25519(recipientPriv, senderPub)
                DtnCrypto.open(root, mid, originNum, destNum, DtnCrypto.SUITE_DM, msg.fragmentIndex, aad, msg.payloadBytes)
            }
            else -> msg.payloadBytes
        }
    }

    fun openBytes(
        sealed: ByteArray,
        messageId: String,
        originNodeId: NodeId,
        destinationNodeId: NodeId,
        channel: Int,
        fragIndex: Int,
        fragTotal: Int,
        suite: Int,
        recipientPriv: ByteArray,
        senderPub: ByteArray,
    ): ByteArray? {
        val mid = uuid16(messageId)
        val originNum = originNodeId.toNodeNum32()
        val destNum = destinationNodeId.toNodeNum32()
        val flags = (suite and 0x07) or (1 shl 6)
        val aad = aad(messageId, originNodeId, destinationNodeId, channel, flags, fragIndex, fragTotal)

        return when (suite) {
            DtnCrypto.SUITE_PLAIN -> sealed
            DtnCrypto.SUITE_DM -> {
                val root = DtnCrypto.x25519(recipientPriv, senderPub)
                DtnCrypto.open(root, mid, originNum, destNum, DtnCrypto.SUITE_DM, fragIndex, aad, sealed)
            }
            else -> sealed
        }
    }

    private fun aad(
        messageId: String, origin: NodeId, dest: NodeId, channel: Int, flags: Int,
        fragIndex: Int, fragTotal: Int
    ): ByteArray {
        val template = DtnMessage(
            id = messageId, originNodeId = origin, destinationNodeId = dest,
            payloadBytes = ByteArray(0), createdAtMs = 0L, ttlMs = 0L,
            messageType = DtnMessageType.DATA, channel = channel,
            fragmentIndex = fragIndex, fragmentTotal = fragTotal, flags = flags,
        )
        return DtnWireCodec.aadHeader(template)
    }

    private fun uuid16(id: String): ByteArray {
        val u = try { UUID.fromString(id) } catch (e: Exception) { UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8)) }
        return ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
    }
}
