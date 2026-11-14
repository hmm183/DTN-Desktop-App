package com.dtn.mesh.service

import com.dtn.mesh.learning.DoubleQLearningEngine
import com.dtn.mesh.learning.ForwardingState
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.ForwardingAction
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.routing.DiMaxPropStrategy
import com.dtn.mesh.routing.MaxPropStrategy
import com.dtn.mesh.routing.ProphetStrategy
import com.dtn.mesh.routing.RoutingStrategy

/**
 * The carrier (mule) rules of one flush pass to one peer, in the order DtnOrchestrator applies them.
 */
object CarrierRules {

    data class Peer(
        val id: NodeId,
        val relay: Boolean = false,
        val isHub: Boolean = false,
        val hubOnly: Boolean = false,
    )

    data class Context(
        val sentTo: (bundleId: String, peerId: String) -> Long,
        val receivedFrom: (bundleId: String) -> Set<String>,
        val deliveredToMe: (bundleId: String) -> Boolean,
        val broadcastSent: (bundleId: String, peerId: String) -> Boolean,
        val carrierHasIt: (bundleId: String, peerId: String) -> Boolean,
        val reachesDirectly: (NodeId) -> Boolean,
        val peerApproved: (bundleId: String, peerId: String) -> Boolean,
        val fallbackApproved: (bundleId: String, peerId: String) -> Boolean,
        val nowMs: Long,
    )

    sealed interface Decision {
        data class Send(val bundle: DtnMessage, val reason: String) : Decision
        data class Hold(val bundleId: String, val reason: String) : Decision
    }

    fun evaluate(bundle: DtnMessage, peer: Peer, ctx: Context, strategy: RoutingStrategy): Decision {
        if (bundle.originNodeId == peer.id) return Decision.Hold(bundle.id, "origin is peer")
        if (ctx.receivedFrom(bundle.id).contains(peer.id.value) && bundle.destinationNodeId != peer.id) {
            return Decision.Hold(bundle.id, "previous hop")
        }

        if (bundle.destinationNodeId == peer.id) {
            return Decision.Send(onWire(bundle, ctx.nowMs), "direct delivery")
        }

        if (bundle.destinationNodeId == NodeId.BROADCAST) {
            if (ctx.broadcastSent(bundle.id, peer.id.value)) {
                return Decision.Hold(bundle.id, "already sent broadcast")
            }
            return Decision.Send(onWire(bundle, ctx.nowMs), "broadcast flood")
        }

        if (peer.hubOnly) {
            return Decision.Hold(bundle.id, "hub only and not destination")
        }

        if (ctx.carrierHasIt(bundle.id, peer.id.value)) {
            return Decision.Hold(bundle.id, "carrier already holds copy")
        }

        val lastSent = ctx.sentTo(bundle.id, peer.id.value)
        if (ctx.nowMs - lastSent < 30_000L) {
            return Decision.Hold(bundle.id, "rate limit 30s")
        }

        if (peer.isHub) {
            return Decision.Send(onWire(bundle, ctx.nowMs), "hub forward")
        }

        if (peer.relay && !ctx.peerApproved(bundle.id, peer.id.value) && !ctx.fallbackApproved(bundle.id, peer.id.value)) {
            return Decision.Hold(bundle.id, "relay peer not approved")
        }

        val rank = strategy.rankForForwarding(listOf(bundle), peer.id)
        if (rank.isNotEmpty()) {
            return Decision.Send(onWire(bundle, ctx.nowMs), "strategy approved (${strategy.name})")
        }

        return Decision.Hold(bundle.id, "strategy veto")
    }

    fun onWire(bundle: DtnMessage, nowMs: Long): DtnMessage =
        bundle.copy(
            hopCount = bundle.hopCount + 1,
            ttlMs = bundle.remainingTtlMs(nowMs)
        )
}
