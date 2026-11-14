package com.dtn.mesh.crypto

import com.dtn.mesh.model.NodeId
import java.io.File

/**
 * Desktop DTN Cryptographic Identity.
 *
 * Holds X25519 (for E2E encryption) and Ed25519 (for signing) keypairs,
 * saved in user home folder (`~/.dtnmesh/identity.bin`) protected by [KeyVault].
 */
class DtnIdentity(
    private val storageDir: File = File(System.getProperty("user.home"), ".dtnmesh"),
    private val vault: KeyVault = KeyVault(storageDir),
) {
    val xPriv: ByteArray
    val xPub: ByteArray
    val edSeed: ByteArray
    val edPub: ByteArray
    val nodeId: NodeId
    val nodeNum32: Int
    val idIsStable: Boolean = true
    val createdAtMs: Long

    init {
        storageDir.mkdirs()
        val file = File(storageDir, "identity.bin")
        val (material, generated) = loadOrCreate(file)
        xPriv = material.copyOfRange(0, 32)
        xPub = material.copyOfRange(32, 64)
        edSeed = material.copyOfRange(64, 96)
        edPub = material.copyOfRange(96, 128)

        val idFile = File(storageDir, "node_id.txt")
        val stored = runCatching { idFile.takeIf { it.exists() }?.readText()?.trim()?.let { NodeId(it).toNodeNum() } }.getOrNull()
        val machineName = System.getenv("COMPUTERNAME") ?: System.getProperty("user.name") ?: "DTN-PC"
        val derived = NodeIdDerivation.derive(machineName, "com.dtn.desktop")
        val keyDerived = DtnCrypto.deriveNodeNum(xPub, edPub)
        nodeNum32 = stored ?: derived ?: keyDerived
        nodeId = NodeId.fromNodeNum(nodeNum32)

        if (stored != nodeNum32) {
            runCatching { idFile.writeText(nodeId.value) }
        }

        createdAtMs = file.lastModified()
        println("[DtnIdentity] Desktop node identity ready: $nodeId (name: $machineName)")
    }

    fun fingerprint(): ByteArray = DtnCrypto.fingerprint(xPub, edPub)

    private fun loadOrCreate(file: File): Pair<ByteArray, Boolean> {
        if (file.exists()) {
            runCatching { vault.unwrap(file.readBytes()) }.getOrNull()?.let { return it to false }
        }
        val (xPriv, xPub) = DtnCrypto.generateX25519()
        val (edSeed, edPub) = DtnCrypto.generateEd25519()
        val raw = xPriv + xPub + edSeed + edPub
        file.writeBytes(vault.wrap(raw))
        return raw to true
    }
}
