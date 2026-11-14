package com.dtn.mesh.model

import com.dtn.mesh.crypto.DtnIdentity

/**
 * Stable NodeId representation for this desktop computer across all transports.
 */
class LocalNodeIdentity(
    private val identity: DtnIdentity
) {
    val nodeId: NodeId get() = identity.nodeId
    val nodeNum32: Int get() = identity.nodeNum32
    val createdAtMs: Long get() = identity.createdAtMs
    val idIsStable: Boolean get() = identity.idIsStable
}
