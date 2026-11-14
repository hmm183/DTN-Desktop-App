package com.dtn.mesh.database

import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.NodeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

data class ChatMessage(
    val id: String,
    val convKey: String,
    val peerNodeId: String?,
    val channel: Int,
    val direction: String, // OUT or IN
    val text: String,
    val status: String,
    val createdAtMs: Long,
    val replyToId: String? = null,
    val replySnippet: String? = null,
)

data class Contact(
    val nodeId: String,
    val deliveryProbability: Double = 0.0,
    val totalEncounters: Int = 0,
    val totalEncounterDurationMs: Long = 0,
    val bestRssi: Int = -100,
    val lastRssi: Int = -100,
    val bestSnr: Float = 0f,
    val lastSnr: Float = 0f,
    val lastEncounterMs: Long = 0,
    val deliverySuccessCount: Int = 0,
    val deliveryAttemptCount: Int = 0,
    val xPub: ByteArray? = null,
    val edPub: ByteArray? = null,
    val keyVerified: Boolean = false,
    val keyFirstSeenMs: Long = 0,
    val announceName: String? = null,
    val isAdded: Boolean = true,
)

class DesktopDatabase(
    private val storageDir: File = File(System.getProperty("user.home"), ".dtnmesh")
) {
    private val dbFile = File(storageDir, "dtn_mesh.db")

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _bufferedMessages = MutableStateFlow<List<DtnMessage>>(emptyList())
    val bufferedMessages: StateFlow<List<DtnMessage>> = _bufferedMessages.asStateFlow()

    init {
        storageDir.mkdirs()
        initTables()
        reloadAll()
    }

    private fun getConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")

    private fun initTables() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id TEXT PRIMARY KEY,
                        conv_key TEXT NOT NULL,
                        peer_node_id TEXT,
                        channel INTEGER NOT NULL DEFAULT 0,
                        direction TEXT NOT NULL,
                        text TEXT NOT NULL,
                        status TEXT NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        reply_to_id TEXT,
                        reply_snippet TEXT
                    );
                """.trimIndent())

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id TEXT PRIMARY KEY,
                        origin_node_id TEXT NOT NULL,
                        destination_node_id TEXT NOT NULL,
                        payload BLOB NOT NULL,
                        ttl_ms INTEGER NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        hop_count INTEGER NOT NULL DEFAULT 0,
                        channel INTEGER NOT NULL DEFAULT 0,
                        last_rssi INTEGER NOT NULL DEFAULT 0,
                        last_snr REAL NOT NULL DEFAULT 0.0,
                        forward_count INTEGER NOT NULL DEFAULT 0,
                        duplicate_count INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL,
                        message_type TEXT NOT NULL DEFAULT 'DATA',
                        fragment_index INTEGER NOT NULL DEFAULT 0,
                        fragment_total INTEGER NOT NULL DEFAULT 1,
                        flags INTEGER NOT NULL DEFAULT 0,
                        buffered_at_ms INTEGER NOT NULL DEFAULT 0
                    );
                """.trimIndent())

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS contacts (
                        node_id TEXT PRIMARY KEY,
                        delivery_probability REAL NOT NULL DEFAULT 0.0,
                        total_encounters INTEGER NOT NULL DEFAULT 0,
                        total_encounter_duration_ms INTEGER NOT NULL DEFAULT 0,
                        best_rssi INTEGER NOT NULL DEFAULT -100,
                        last_rssi INTEGER NOT NULL DEFAULT -100,
                        best_snr REAL NOT NULL DEFAULT 0.0,
                        last_snr REAL NOT NULL DEFAULT 0.0,
                        last_encounter_ms INTEGER NOT NULL DEFAULT 0,
                        delivery_success_count INTEGER NOT NULL DEFAULT 0,
                        delivery_attempt_count INTEGER NOT NULL DEFAULT 0,
                        x_pub BLOB,
                        ed_pub BLOB,
                        key_verified INTEGER NOT NULL DEFAULT 0,
                        key_first_seen_ms INTEGER NOT NULL DEFAULT 0,
                        announce_name TEXT,
                        is_added INTEGER NOT NULL DEFAULT 1
                    );
                """.trimIndent())

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS encounters (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        peer_node_id TEXT NOT NULL,
                        start_time_ms INTEGER NOT NULL,
                        end_time_ms INTEGER NOT NULL,
                        best_rssi INTEGER NOT NULL,
                        best_snr REAL NOT NULL
                    );
                """.trimIndent())

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS seen_bundles (
                        message_id TEXT PRIMARY KEY,
                        outcome TEXT NOT NULL,
                        recorded_at_ms INTEGER NOT NULL,
                        expires_at_ms INTEGER NOT NULL
                    );
                """.trimIndent())
            }
        }
    }

    fun reloadAll() {
        reloadChatMessages()
        reloadContacts()
        reloadBuffered()
    }

    private fun reloadChatMessages() {
        val list = mutableListOf<ChatMessage>()
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT * FROM chat_messages ORDER BY created_at_ms ASC")
                while (rs.next()) {
                    list.add(
                        ChatMessage(
                            id = rs.getString("id"),
                            convKey = rs.getString("conv_key"),
                            peerNodeId = rs.getString("peer_node_id"),
                            channel = rs.getInt("channel"),
                            direction = rs.getString("direction"),
                            text = rs.getString("text"),
                            status = rs.getString("status"),
                            createdAtMs = rs.getLong("created_at_ms"),
                            replyToId = rs.getString("reply_to_id"),
                            replySnippet = rs.getString("reply_snippet")
                        )
                    )
                }
            }
        }
        _chatMessages.value = list
    }

    fun insertChatMessage(msg: ChatMessage) {
        getConnection().use { conn ->
            val ps = conn.prepareStatement("""
                INSERT OR REPLACE INTO chat_messages 
                (id, conv_key, peer_node_id, channel, direction, text, status, created_at_ms, reply_to_id, reply_snippet)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent())
            ps.setString(1, msg.id)
            ps.setString(2, msg.convKey)
            ps.setString(3, msg.peerNodeId)
            ps.setInt(4, msg.channel)
            ps.setString(5, msg.direction)
            ps.setString(6, msg.text)
            ps.setString(7, msg.status)
            ps.setLong(8, msg.createdAtMs)
            ps.setString(9, msg.replyToId)
            ps.setString(10, msg.replySnippet)
            ps.executeUpdate()
        }
        reloadChatMessages()
    }

    fun updateChatStatus(msgId: String, status: String) {
        getConnection().use { conn ->
            val ps = conn.prepareStatement("UPDATE chat_messages SET status = ? WHERE id = ?")
            ps.setString(1, status)
            ps.setString(2, msgId)
            ps.executeUpdate()
        }
        reloadChatMessages()
    }

    private fun reloadContacts() {
        val list = mutableListOf<Contact>()
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT * FROM contacts ORDER BY last_encounter_ms DESC")
                while (rs.next()) {
                    list.add(
                        Contact(
                            nodeId = rs.getString("node_id"),
                            deliveryProbability = rs.getDouble("delivery_probability"),
                            totalEncounters = rs.getInt("total_encounters"),
                            totalEncounterDurationMs = rs.getLong("total_encounter_duration_ms"),
                            bestRssi = rs.getInt("best_rssi"),
                            lastRssi = rs.getInt("last_rssi"),
                            bestSnr = rs.getFloat("best_snr"),
                            lastSnr = rs.getFloat("last_snr"),
                            lastEncounterMs = rs.getLong("last_encounter_ms"),
                            deliverySuccessCount = rs.getInt("delivery_success_count"),
                            deliveryAttemptCount = rs.getInt("delivery_attempt_count"),
                            xPub = rs.getBytes("x_pub"),
                            edPub = rs.getBytes("ed_pub"),
                            keyVerified = rs.getInt("key_verified") == 1,
                            keyFirstSeenMs = rs.getLong("key_first_seen_ms"),
                            announceName = rs.getString("announce_name"),
                            isAdded = rs.getInt("is_added") == 1
                        )
                    )
                }
            }
        }
        _contacts.value = list
    }

    fun upsertContact(c: Contact) {
        getConnection().use { conn ->
            val ps = conn.prepareStatement("""
                INSERT INTO contacts (
                    node_id, delivery_probability, total_encounters, total_encounter_duration_ms,
                    best_rssi, last_rssi, best_snr, last_snr, last_encounter_ms,
                    delivery_success_count, delivery_attempt_count, x_pub, ed_pub,
                    key_verified, key_first_seen_ms, announce_name, is_added
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(node_id) DO UPDATE SET
                    delivery_probability = excluded.delivery_probability,
                    total_encounters = excluded.total_encounters,
                    last_rssi = excluded.last_rssi,
                    last_encounter_ms = excluded.last_encounter_ms,
                    announce_name = coalesce(excluded.announce_name, contacts.announce_name),
                    x_pub = coalesce(excluded.x_pub, contacts.x_pub),
                    ed_pub = coalesce(excluded.ed_pub, contacts.ed_pub),
                    key_verified = case when excluded.key_verified = 1 then 1 else contacts.key_verified end,
                    key_first_seen_ms = coalesce(contacts.key_first_seen_ms, excluded.key_first_seen_ms),
                    is_added = max(contacts.is_added, excluded.is_added)
            """.trimIndent())
            ps.setString(1, c.nodeId)
            ps.setDouble(2, c.deliveryProbability)
            ps.setInt(3, c.totalEncounters)
            ps.setLong(4, c.totalEncounterDurationMs)
            ps.setInt(5, c.bestRssi)
            ps.setInt(6, c.lastRssi)
            ps.setFloat(7, c.bestSnr)
            ps.setFloat(8, c.lastSnr)
            ps.setLong(9, c.lastEncounterMs)
            ps.setInt(10, c.deliverySuccessCount)
            ps.setInt(11, c.deliveryAttemptCount)
            ps.setBytes(12, c.xPub)
            ps.setBytes(13, c.edPub)
            ps.setInt(14, if (c.keyVerified) 1 else 0)
            ps.setLong(15, c.keyFirstSeenMs)
            ps.setString(16, c.announceName)
            ps.setInt(17, if (c.isAdded) 1 else 0)
            ps.executeUpdate()
        }
        reloadContacts()
    }

    fun storeBufferedMessage(msg: DtnMessage) {
        getConnection().use { conn ->
            val ps = conn.prepareStatement("""
                INSERT OR REPLACE INTO messages 
                (id, origin_node_id, destination_node_id, payload, ttl_ms, created_at_ms,
                 hop_count, channel, last_rssi, last_snr, forward_count, duplicate_count,
                 status, message_type, fragment_index, fragment_total, flags, buffered_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent())
            ps.setString(1, msg.id)
            ps.setString(2, msg.originNodeId.value)
            ps.setString(3, msg.destinationNodeId.value)
            ps.setBytes(4, msg.payloadBytes)
            ps.setLong(5, msg.ttlMs)
            ps.setLong(6, msg.createdAtMs)
            ps.setInt(7, msg.hopCount)
            ps.setInt(8, msg.channel)
            ps.setInt(9, msg.lastRssi)
            ps.setFloat(10, msg.lastSnr)
            ps.setInt(11, msg.forwardCount)
            ps.setInt(12, msg.duplicateCount)
            ps.setString(13, "BUFFERED")
            ps.setString(14, msg.messageType.name)
            ps.setInt(15, msg.fragmentIndex)
            ps.setInt(16, msg.fragmentTotal)
            ps.setInt(17, msg.flags)
            ps.setLong(18, if (msg.bufferedAtMs > 0) msg.bufferedAtMs else System.currentTimeMillis())
            ps.executeUpdate()
        }
        reloadBuffered()
    }

    fun removeBufferedMessage(id: String) {
        getConnection().use { conn ->
            val ps = conn.prepareStatement("DELETE FROM messages WHERE id = ?")
            ps.setString(1, id)
            ps.executeUpdate()
        }
        reloadBuffered()
    }

    private fun reloadBuffered() {
        val list = mutableListOf<DtnMessage>()
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT * FROM messages WHERE status = 'BUFFERED' ORDER BY created_at_ms ASC")
                while (rs.next()) {
                    list.add(
                        DtnMessage(
                            id = rs.getString("id"),
                            originNodeId = NodeId(rs.getString("origin_node_id")),
                            destinationNodeId = NodeId(rs.getString("destination_node_id")),
                            payloadBytes = rs.getBytes("payload"),
                            ttlMs = rs.getLong("ttl_ms"),
                            createdAtMs = rs.getLong("created_at_ms"),
                            hopCount = rs.getInt("hop_count"),
                            channel = rs.getInt("channel"),
                            lastRssi = rs.getInt("last_rssi"),
                            lastSnr = rs.getFloat("last_snr"),
                            forwardCount = rs.getInt("forward_count"),
                            duplicateCount = rs.getInt("duplicate_count"),
                            messageType = DtnMessageType.valueOf(rs.getString("message_type")),
                            fragmentIndex = rs.getInt("fragment_index"),
                            fragmentTotal = rs.getInt("fragment_total"),
                            flags = rs.getInt("flags"),
                            bufferedAtMs = rs.getLong("buffered_at_ms")
                        )
                    )
                }
            }
        }
        _bufferedMessages.value = list
    }

    fun isSeen(msgId: String): Boolean {
        getConnection().use { conn ->
            val ps = conn.prepareStatement("SELECT 1 FROM seen_bundles WHERE message_id = ?")
            ps.setString(1, msgId)
            val rs = ps.executeQuery()
            return rs.next()
        }
    }

    fun markSeen(msgId: String, outcome: String = "DELIVERED") {
        getConnection().use { conn ->
            val ps = conn.prepareStatement("""
                INSERT OR REPLACE INTO seen_bundles (message_id, outcome, recorded_at_ms, expires_at_ms)
                VALUES (?, ?, ?, ?)
            """.trimIndent())
            val now = System.currentTimeMillis()
            ps.setString(1, msgId)
            ps.setString(2, outcome)
            ps.setLong(3, now)
            ps.setLong(4, now + 86400000L)
            ps.executeUpdate()
        }
    }
}
