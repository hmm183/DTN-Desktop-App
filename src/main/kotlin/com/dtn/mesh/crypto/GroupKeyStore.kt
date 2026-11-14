package com.dtn.mesh.crypto

import java.util.concurrent.ConcurrentHashMap

/**
 * Manages group/channel keys in-memory on Desktop.
 */
class GroupKeyStore(
    private val vault: KeyVault
) {
    private val cache = ConcurrentHashMap<Int, ByteArray>()

    fun key(channel: Int): ByteArray? {
        if (channel <= 0) return null
        return cache[channel]
    }

    fun setKey(channel: Int, key: ByteArray) {
        cache[channel] = key
    }
}
