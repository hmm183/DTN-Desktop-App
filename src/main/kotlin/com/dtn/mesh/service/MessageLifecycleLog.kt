package com.dtn.mesh.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

enum class LifecycleEvent {
    CREATED, BUFFERED, FORWARDED, FORWARDING, DELIVERED, EXPIRED, DROPPED, ACK_RECEIVED, BUFFER_CLEARED, DUPLICATE, HOP_LIMIT, IGNORED, ACK_GENERATED
}

enum class DiagLevel { DEBUG, INFO, WARN, ERROR }

data class DiagLine(
    val atMs: Long,
    val level: DiagLevel,
    val source: String,
    val text: String
)

data class LevelCounts(
    val all: Int = 0,
    val info: Int = 0,
    val warn: Int = 0,
    val error: Int = 0,
    val debug: Int = 0
)

data class LifecycleEntry(
    val timestamp: String,
    val event: LifecycleEvent,
    val msgId: String,
    val origin: String,
    val dest: String,
    val hopCount: Int = -1,
    val forwardCount: Int = -1,
    val ttlRemainingMin: Int = -1,
    val extra: String = "",
    val epochMs: Long = System.currentTimeMillis()
)

class MessageLifecycleLog(
    private val storageDir: File = File(System.getProperty("user.home"), ".dtnmesh")
) {
    data class GroupReceipt(val member: String, val atMs: Long, val path: String = "", val hop: String = "")

    data class RouteRecord(
        val msgId: String,
        val source: String,
        val destination: String,
        val prevHop: String? = null,
        val me: String = "DESKTOP",
        val nextHop: String? = null,
        val delivered: Boolean = false,
        val timestampMs: Long = System.currentTimeMillis(),
        val strategy: String = "EPIDEMIC",
        val fullId: String = "",
        val channel: Int = 0,
        val createdAtMs: Long = System.currentTimeMillis(),
        val lastPath: String = "",
        val outcome: String = "",
        val deliveredAtMs: Long = 0L,
        val decision: String = "",
        val decisionAtMs: Long = 0L,
        val senderStrategy: String = "",
        val groupReceipts: List<GroupReceipt> = emptyList(),
        val fallbackVia: String = "",
        val fallbackAfter: String = "",
    ) {
        val fallbackLinks: List<String> get() = fallbackVia.split(',').filter { it.isNotBlank() }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _entries = MutableStateFlow<List<LifecycleEntry>>(emptyList())
    val entries: StateFlow<List<LifecycleEntry>> = _entries.asStateFlow()

    private val _routes = MutableStateFlow<List<RouteRecord>>(emptyList())
    val routes: StateFlow<List<RouteRecord>> = _routes.asStateFlow()

    private val _console = MutableStateFlow<List<String>>(emptyList())
    val console: StateFlow<List<String>> = _console.asStateFlow()

    private val _diagLines = MutableStateFlow<List<DiagLine>>(emptyList())
    val diagLines: StateFlow<List<DiagLine>> = _diagLines.asStateFlow()

    private val _counts = MutableStateFlow(LevelCounts())
    val counts: StateFlow<LevelCounts> = _counts.asStateFlow()

    private val journalFile = File(storageDir, "message_journal_v1.log")

    init {
        storageDir.mkdirs()
        loadJournal()
    }

    fun logEvent(event: LifecycleEvent, msgId: String, origin: String, dest: String, extra: String = "") {
        val entry = LifecycleEntry(
            timestamp = timeFormat.format(Date()),
            event = event,
            msgId = msgId.take(8),
            origin = origin,
            dest = dest,
            extra = extra
        )
        synchronized(this) {
            _entries.value = (listOf(entry) + _entries.value).take(1000)
        }
        scope.launch {
            runCatching {
                journalFile.appendText(JournalCodec.encodeEvent(entry) + "\n")
            }
        }
    }

    fun logDiag(level: DiagLevel, source: String, text: String) {
        val now = System.currentTimeMillis()
        val line = DiagLine(now, level, source, text)
        val formatted = "[${timeFormat.format(Date(now))}] ${level.name} $source · $text"
        println(formatted)
        synchronized(this) {
            val updated = (listOf(line) + _diagLines.value).take(1500)
            _diagLines.value = updated
            _counts.value = LevelCounts(
                all = updated.size,
                info = updated.count { it.level == DiagLevel.INFO },
                warn = updated.count { it.level == DiagLevel.WARN },
                error = updated.count { it.level == DiagLevel.ERROR },
                debug = updated.count { it.level == DiagLevel.DEBUG }
            )
            _console.value = (listOf(formatted) + _console.value).take(500)
        }
    }

    fun d(source: String, text: String) = logDiag(DiagLevel.DEBUG, source, text)
    fun i(source: String, text: String) = logDiag(DiagLevel.INFO, source, text)
    fun w(source: String, text: String) = logDiag(DiagLevel.WARN, source, text)
    fun e(source: String, text: String) = logDiag(DiagLevel.ERROR, source, text)

    fun logConsole(tag: String, text: String) = i(tag, text)

    fun recordRoute(route: RouteRecord) {
        synchronized(this) {
            _routes.value = (_routes.value.filterNot { it.msgId == route.msgId } + route).take(300)
        }
        scope.launch {
            runCatching {
                journalFile.appendText(JournalCodec.encodeRoute(route) + "\n")
            }
        }
    }

    private fun loadJournal() {
        if (!journalFile.exists()) return
        scope.launch {
            runCatching {
                val loadedEvents = mutableListOf<LifecycleEntry>()
                val loadedRoutes = mutableListOf<RouteRecord>()
                journalFile.forEachLine { line ->
                    val decoded = JournalCodec.decode(line) { timeFormat.format(Date(it)) }
                    decoded?.event?.let { loadedEvents.add(it) }
                    decoded?.route?.let { loadedRoutes.add(it) }
                }
                _entries.value = loadedEvents.takeLast(1000).reversed()
                _routes.value = loadedRoutes.takeLast(300)
            }
        }
    }
}
