package com.dtn.mesh.crypto

import com.dtn.mesh.model.NodeId
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Universal link and direct code generator for DTN Mesh identities and groups (Desktop).
 */
object LinkGenerator {

    var workerHost: String = "dtnmesh.vrishankrainaphotos1.workers.dev"

    /**
     * Builds an invite link or raw identity code for a peer.
     */
    fun buildContactLink(nodeId: NodeId, xPub: ByteArray, edPub: ByteArray, name: String): String {
        val code = IdentityQr.build(nodeId.toNodeNum32(), xPub, edPub, name)
        return if (workerHost.isNotBlank()) "https://$workerHost/c#$code" else code
    }

    /**
     * Builds a group join link or raw group invite code.
     */
    fun buildGroupLink(channel: Int, name: String, key: ByteArray): String {
        val code = GroupInvite.build(channel, name, key)
        return if (workerHost.isNotBlank()) "https://$workerHost/g#$code" else code
    }

    /**
     * Copies text to the Windows system clipboard.
     */
    fun copyToClipboard(text: String) {
        try {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
