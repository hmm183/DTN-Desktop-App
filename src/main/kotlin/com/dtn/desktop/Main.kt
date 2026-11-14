package com.dtn.desktop

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import com.dtn.desktop.theme.DtnColors
import com.dtn.desktop.theme.DtnLightColorScheme
import com.dtn.desktop.theme.DtnShapes
import com.dtn.desktop.theme.DtnType
import com.dtn.mesh.crypto.DtnIdentity
import com.dtn.mesh.crypto.KeyVault
import com.dtn.mesh.database.ChatMessage
import com.dtn.mesh.database.Contact
import com.dtn.mesh.database.DesktopDatabase
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.routing.StrategySelector.StrategyType
import com.dtn.mesh.service.DesktopOrchestrator
import com.dtn.mesh.service.LifecycleEntry
import com.dtn.mesh.service.LifecycleEvent
import com.dtn.mesh.service.MessageLifecycleLog
import com.dtn.mesh.transport.PeerEndpoint
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import com.dtn.desktop.simulation.DiMaxPropSimulationView

enum class DesktopNavTab(val label: String, val icon: ImageVector) {
    CHATS("Chats", Icons.AutoMirrored.Filled.Chat),
    QUEUE("Queue", Icons.Filled.Inbox),
    NETWORK("Network", Icons.Filled.Hub),
    SIMULATION("DiMaxProp", Icons.Filled.Timeline),
    JOURNAL("Event Log", Icons.Filled.History),
    CONSOLE("Console", Icons.Filled.Terminal)
}

fun copyToClipboard(text: String) {
    try {
        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
    } catch (_: Exception) {}
}

fun ByteArray.toHexString(maxBytes: Int = 48): String {
    val slice = this.take(maxBytes)
    val hex = slice.joinToString(" ") { String.format("%02X", it) }
    return if (this.size > maxBytes) "$hex … (${this.size} B total)" else hex
}

fun main() {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        System.err.println("FATAL: Uncaught exception on thread ${thread.name}:")
        throwable.printStackTrace()
        try {
            val logFile = File(File(System.getProperty("user.home"), ".dtnmesh"), "crash.log")
            logFile.parentFile?.mkdirs()
            logFile.writeText("CRASH at ${Date()} on thread [${thread.name}]:\n" + throwable.stackTraceToString())
            javax.swing.JOptionPane.showMessageDialog(
                null,
                "DTN Desktop encountered an unhandled error:\n\n${throwable.javaClass.simpleName}: ${throwable.message}\n\nCrash log saved to:\n${logFile.absolutePath}",
                "DTN Desktop — Error",
                javax.swing.JOptionPane.ERROR_MESSAGE
            )
        } catch (_: Exception) {}
    }

    application {
        val storageDir = remember { File(System.getProperty("user.home"), ".dtnmesh").apply { mkdirs() } }
        val vault = remember { KeyVault(storageDir) }
        val identity = remember { DtnIdentity(storageDir, vault) }
        val database = remember { DesktopDatabase(storageDir) }
        val lifecycleLog = remember { MessageLifecycleLog(storageDir) }
        val orchestrator = remember { DesktopOrchestrator(identity, database, lifecycleLog) }

        LaunchedEffect(Unit) {
            orchestrator.start()
        }

        val windowState = rememberWindowState(
            position = WindowPosition(Alignment.Center),
            size = DpSize(1300.dp, 860.dp)
        )

        Window(
            onCloseRequest = {
                orchestrator.stop()
                exitApplication()
            },
            title = "DTN Mesh Relay — Desktop Command Station (${identity.nodeId})",
            state = windowState
        ) {
            MaterialTheme(colorScheme = DtnLightColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = DtnColors.Background) {
                    DesktopAppScreen(orchestrator, database, lifecycleLog)
                }
            }
        }
    }
}

@Composable
fun DesktopAppScreen(
    orchestrator: DesktopOrchestrator,
    database: DesktopDatabase,
    lifecycleLog: MessageLifecycleLog,
) {
    var activeTab by remember { mutableStateOf(DesktopNavTab.CHATS) }
    var selectedConvKey by remember { mutableStateOf("^all") }
    var inspectingMessage by remember { mutableStateOf<ChatMessage?>(null) }

    val chatMessages by database.chatMessages.collectAsState()
    val contacts by database.contacts.collectAsState()
    val bufferedMessages by database.bufferedMessages.collectAsState()
    val onlinePeers by orchestrator.transportManager.onlinePeers.collectAsState()
    val activeStrategy by orchestrator.activeStrategy.collectAsState()
    val journalEntries by lifecycleLog.entries.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // ── TOP NAVIGATION & STATUS BAR ───────────────────────────────────────
        TopNavigationBar(
            localNodeId = orchestrator.identity.nodeId.value,
            orchestrator = orchestrator,
            onlinePeersCount = onlinePeers.size,
            activeStrategy = activeStrategy,
            onSelectStrategy = { orchestrator.setStrategy(it) }
        )

        HorizontalDivider(thickness = 1.dp, color = DtnColors.CardBorder)

        // ── 3-PANE MAIN CONTENT AREA ──────────────────────────────────────────
        Row(modifier = Modifier.fillMaxSize()) {
            // LEFT COLUMN (330.dp): Tabs & Peer List
            Surface(
                modifier = Modifier.width(330.dp).fillMaxHeight(),
                color = DtnColors.Card,
            ) {
                LeftSidebar(
                    activeTab = activeTab,
                    onTabSelected = { activeTab = it },
                    contacts = contacts,
                    onlinePeers = onlinePeers,
                    selectedConvKey = selectedConvKey,
                    onSelectConversation = { selectedConvKey = it },
                    bufferedCount = bufferedMessages.size,
                    journalCount = journalEntries.size,
                    onAddContact = { id, name ->
                        database.upsertContact(
                            Contact(
                                nodeId = id,
                                announceName = name,
                                keyVerified = false,
                                keyFirstSeenMs = System.currentTimeMillis()
                            )
                        )
                    }
                )
            }

            VerticalDivider(thickness = 1.dp, color = DtnColors.CardBorder)

            // CENTER COLUMN (Weight 1f): Active Tab Content
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(DtnColors.Background)) {
                when (activeTab) {
                    DesktopNavTab.CHATS -> {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                ChatView(
                                    convKey = selectedConvKey,
                                    messages = chatMessages.filter { it.convKey == selectedConvKey },
                                    contacts = contacts,
                                    onlinePeers = onlinePeers,
                                    onSendMessage = { text ->
                                        val dest = if (selectedConvKey == "^all") NodeId.BROADCAST else NodeId(selectedConvKey)
                                        orchestrator.sendTextMessage(dest, text)
                                    },
                                    onMessageClick = { msg ->
                                        inspectingMessage = msg
                                    },
                                    onClearChat = {
                                        database.clearConversation(selectedConvKey)
                                    },
                                    onPingPeer = {
                                        val dest = if (selectedConvKey == "^all") NodeId.BROADCAST else NodeId(selectedConvKey)
                                        orchestrator.sendTextMessage(dest, "⚡ Mesh Ping from Station ${orchestrator.identity.nodeId.value}")
                                    }
                                )
                            }

                            VerticalDivider(thickness = 1.dp, color = DtnColors.CardBorder)

                            // RIGHT COLUMN: Peer Inspector (300.dp)
                            Surface(
                                modifier = Modifier.width(300.dp).fillMaxHeight(),
                                color = DtnColors.Card
                            ) {
                                NodeInspectorPanel(
                                    selectedConvKey = selectedConvKey,
                                    contacts = contacts,
                                    onlinePeers = onlinePeers,
                                    orchestrator = orchestrator
                                )
                            }
                        }
                    }
                    DesktopNavTab.QUEUE -> {
                        QueueInspectorView(
                            buffered = bufferedMessages,
                            orchestrator = orchestrator,
                            contacts = contacts
                        )
                    }
                    DesktopNavTab.NETWORK -> {
                        NetworkTopologyView(
                            orchestrator = orchestrator,
                            onlinePeers = onlinePeers,
                            contacts = contacts,
                            onOpenChat = { peerId ->
                                selectedConvKey = peerId
                                activeTab = DesktopNavTab.CHATS
                            }
                        )
                    }
                    DesktopNavTab.JOURNAL -> {
                        JournalLogView(
                            lifecycleLog = lifecycleLog,
                            onInspectMsgId = { id ->
                                val msg = chatMessages.firstOrNull { it.id.startsWith(id) || id.startsWith(it.id.take(8)) }
                                if (msg != null) inspectingMessage = msg
                            }
                        )
                    }
                    DesktopNavTab.SIMULATION -> {
                        DiMaxPropSimulationView()
                    }
                    DesktopNavTab.CONSOLE -> {
                        ConsoleLogView(lifecycleLog)
                    }
                }
            }
        }
    }

    // Message Details Modal / Inspector
    if (inspectingMessage != null) {
        MessageDetailsDialog(
            msg = inspectingMessage!!,
            orchestrator = orchestrator,
            lifecycleLog = lifecycleLog,
            contacts = contacts,
            onClose = { inspectingMessage = null }
        )
    }
}

// ── TOP NAVIGATION BAR ────────────────────────────────────────────────────────
@Composable
fun TopNavigationBar(
    localNodeId: String,
    orchestrator: DesktopOrchestrator,
    onlinePeersCount: Int,
    activeStrategy: StrategyType,
    onSelectStrategy: (StrategyType) -> Unit,
) {
    val usbSerialConnected by orchestrator.transportManager.usbSerial.isConnected.collectAsState()
    val bleScanning by orchestrator.transportManager.ble.isScanning.collectAsState()
    val wifiListening by orchestrator.transportManager.wifiDirect.isListening.collectAsState()
    val internetConnected by orchestrator.transportManager.internet.isConnected.collectAsState()
    val discoverMode by orchestrator.discoverMode.collectAsState()

    var linkCopiedNotification by remember { mutableStateOf(false) }

    Surface(
        color = DtnColors.Card,
        modifier = Modifier.fillMaxWidth().height(64.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: App Title + Local Node Badge
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "DTN Mesh ",
                        style = DtnType.AppTitle
                    )
                    Text(
                        text = "Relay",
                        style = DtnType.AppTitle.copy(color = DtnColors.Primary)
                    )
                }

                Surface(
                    shape = DtnShapes.Pill,
                    color = DtnColors.PrimarySoft,
                    modifier = Modifier.height(28.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(DtnColors.Primary))
                        Text(
                            text = "Station $localNodeId",
                            style = DtnType.Mono.copy(fontWeight = FontWeight.Bold, color = DtnColors.Primary)
                        )
                    }
                }
            }

            // Center: 4 Live Transport Status Pills
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransportChip(
                    name = "USB LoRa",
                    active = usbSerialConnected,
                    activeColor = DtnColors.Green,
                    inactiveText = "Off"
                )
                TransportChip(
                    name = "Local Wi-Fi",
                    active = wifiListening,
                    activeColor = DtnColors.Green,
                    inactiveText = "Off"
                )
                TransportChip(
                    name = "BLE Active",
                    active = bleScanning,
                    activeColor = DtnColors.Green,
                    inactiveText = "Scanning"
                )
                TransportChip(
                    name = "Internet Relay",
                    active = internetConnected,
                    activeColor = DtnColors.Green,
                    inactiveText = "Connecting"
                )
            }

            // Right: Strategy Selector + Discover Mode Toggle + Share Invite Link
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StrategySelectorPill(activeStrategy, onSelectStrategy)

                // Discover Mode Toggle Pill (Replaced Announce button)
                Surface(
                    shape = DtnShapes.Pill,
                    color = if (discoverMode) DtnColors.GreenSoft else DtnColors.GreySoft,
                    border = BorderStroke(1.dp, if (discoverMode) DtnColors.Green.copy(alpha = 0.4f) else DtnColors.CardBorder),
                    modifier = Modifier.height(36.dp).clip(DtnShapes.Pill).clickable {
                        orchestrator.setDiscoverMode(!discoverMode)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (discoverMode) DtnColors.Green else DtnColors.TextHint)
                        )
                        Text(
                            text = if (discoverMode) "Discover: ON" else "Discover: OFF",
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (discoverMode) DtnColors.GreenText else DtnColors.TextSubtitle
                            )
                        )
                        Switch(
                            checked = discoverMode,
                            onCheckedChange = { orchestrator.setDiscoverMode(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DtnColors.Green,
                                checkedTrackColor = DtnColors.GreenSoft,
                                uncheckedThumbColor = DtnColors.TextHint,
                                uncheckedTrackColor = DtnColors.CardBorder
                            ),
                            modifier = Modifier.scale(0.7f).height(20.dp)
                        )
                    }
                }

                // Share Link Shortcut
                OutlinedButton(
                    onClick = {
                        val inviteLink = orchestrator.buildMyInviteLink()
                        copyToClipboard(inviteLink)
                        linkCopiedNotification = true
                    },
                    shape = DtnShapes.Button,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(
                        if (linkCopiedNotification) Icons.Filled.Check else Icons.Filled.Link,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = if (linkCopiedNotification) DtnColors.GreenText else DtnColors.Primary
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (linkCopiedNotification) "Link Copied!" else "Share Link",
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (linkCopiedNotification) DtnColors.GreenText else DtnColors.Primary
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun TransportChip(name: String, active: Boolean, activeColor: Color, inactiveText: String) {
    Surface(
        shape = DtnShapes.Pill,
        color = if (active) DtnColors.GreenSoft else DtnColors.GreySoft,
        modifier = Modifier.height(26.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier.size(6.dp).clip(CircleShape).background(if (active) activeColor else DtnColors.Grey)
            )
            Text(
                text = "$name: ${if (active) "Active" else inactiveText}",
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (active) DtnColors.GreenText else DtnColors.TextSubtitle
                )
            )
        }
    }
}

@Composable
fun StrategySelectorPill(activeStrategy: StrategyType, onSelectStrategy: (StrategyType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val color = DtnColors.algorithm(activeStrategy.name)

    Box {
        Surface(
            shape = DtnShapes.Pill,
            color = color.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
            modifier = Modifier.height(32.dp).clickable { expanded = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(
                    text = activeStrategy.name,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            StrategyType.entries.forEach { strat ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(DtnColors.algorithm(strat.name)))
                            Text(strat.name, fontWeight = if (strat == activeStrategy) FontWeight.Bold else FontWeight.Normal)
                        }
                    },
                    onClick = {
                        onSelectStrategy(strat)
                        expanded = false
                    }
                )
            }
        }
    }
}

// ── LEFT SIDEBAR ─────────────────────────────────────────────────────────────
@Composable
fun LeftSidebar(
    activeTab: DesktopNavTab,
    onTabSelected: (DesktopNavTab) -> Unit,
    contacts: List<Contact>,
    onlinePeers: Map<String, Any>,
    selectedConvKey: String,
    onSelectConversation: (String) -> Unit,
    bufferedCount: Int,
    journalCount: Int,
    onAddContact: (String, String) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // Navigation Tabs (2-row grid for 6 tabs)
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = DtnColors.Track,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val row1 = listOf(DesktopNavTab.CHATS, DesktopNavTab.QUEUE, DesktopNavTab.NETWORK)
                val row2 = listOf(DesktopNavTab.SIMULATION, DesktopNavTab.JOURNAL, DesktopNavTab.CONSOLE)
                listOf(row1, row2).forEach { tabRow ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(34.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        tabRow.forEach { tab ->
                            val isSelected = activeTab == tab
                            Surface(
                                modifier = Modifier.weight(1f).fillMaxHeight().clickable { onTabSelected(tab) },
                                shape = RoundedCornerShape(7.dp),
                                color = if (isSelected) DtnColors.Card else Color.Transparent
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                            tint = if (isSelected) DtnColors.Primary else DtnColors.TextSubtitle
                                        )
                                        Text(
                                            text = tab.label,
                                            style = TextStyle(
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                color = if (isSelected) DtnColors.Primary else DtnColors.TextSubtitle
                                            )
                                        )
                                        if (tab == DesktopNavTab.QUEUE && bufferedCount > 0) {
                                            Surface(shape = CircleShape, color = DtnColors.Orange) {
                                                Text(
                                                    text = "$bufferedCount",
                                                    modifier = Modifier.padding(horizontal = 4.dp),
                                                    style = TextStyle(fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Search Bar
        Surface(
            shape = DtnShapes.Pill,
            color = DtnColors.Track,
            modifier = Modifier.fillMaxWidth().height(38.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = DtnColors.TextHint, modifier = Modifier.size(15.dp))
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    textStyle = TextStyle(fontSize = 12.sp, color = DtnColors.TextTitle),
                    placeholder = { Text("Search mesh peers...", style = TextStyle(fontSize = 12.sp, color = DtnColors.TextHint)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Conversations List
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Pinned Broadcast Channel
            item {
                ConversationRowItem(
                    title = "Broadcast Channel",
                    subtitle = "Public mesh flood (^all)",
                    avatarColor = DtnColors.GroupAvatar,
                    initial = "📢",
                    isSelected = selectedConvKey == "^all",
                    isOnline = true,
                    onClick = { onSelectConversation("^all") }
                )
            }

            val filteredContacts = contacts.filter {
                searchQuery.isBlank() ||
                (it.announceName != null && it.announceName.contains(searchQuery, ignoreCase = true)) ||
                it.nodeId.contains(searchQuery, ignoreCase = true)
            }

            items(filteredContacts) { contact ->
                val isOnline = onlinePeers.containsKey(contact.nodeId)
                val displayName = contact.announceName?.takeIf { it.isNotBlank() } ?: contact.nodeId
                ConversationRowItem(
                    title = displayName,
                    subtitle = if (isOnline) "● Online nearby" else contact.nodeId,
                    avatarColor = DtnColors.avatarFor(contact.nodeId),
                    initial = contact.announceName?.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    isSelected = selectedConvKey == contact.nodeId,
                    isOnline = isOnline,
                    onClick = { onSelectConversation(contact.nodeId) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Bottom Add Contact Button
        OutlinedButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth().height(38.dp),
            shape = DtnShapes.Button,
            border = BorderStroke(1.dp, DtnColors.CardBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = DtnColors.Primary)
        ) {
            Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add Peer Manually", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        }
    }

    if (showAddDialog) {
        AddContactModal(
            onDismiss = { showAddDialog = false },
            onAdd = { id, name ->
                onAddContact(id, name)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun ConversationRowItem(
    title: String,
    subtitle: String,
    avatarColor: Color,
    initial: String,
    isSelected: Boolean,
    isOnline: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = DtnShapes.Button,
        color = if (isSelected) DtnColors.PrimarySoft else Color.Transparent,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Avatar Circle with live status ring
            Box(contentAlignment = Alignment.BottomEnd) {
                Surface(
                    shape = CircleShape,
                    color = avatarColor,
                    border = if (isOnline) BorderStroke(2.dp, DtnColors.Green) else null,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = initial,
                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        )
                    }
                }
                if (isOnline) {
                    Box(
                        modifier = Modifier.size(10.dp).clip(CircleShape).background(DtnColors.Green)
                            .border(1.5.dp, Color.White, CircleShape)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = DtnType.RowTitle.copy(
                        fontSize = 13.sp,
                        color = if (isSelected) DtnColors.Primary else DtnColors.TextTitle
                    ),
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    style = DtnType.RowSubtitle.copy(fontSize = 11.sp),
                    maxLines = 1
                )
            }
        }
    }
}

// ── HIGH-POLISH CHAT VIEW ───────────────────────────────────────────────────
@Composable
fun ChatView(
    convKey: String,
    messages: List<ChatMessage>,
    contacts: List<Contact>,
    onlinePeers: Map<String, Any>,
    onSendMessage: (String) -> Unit,
    onMessageClick: (ChatMessage) -> Unit,
    onClearChat: (() -> Unit)? = null,
    onPingPeer: (() -> Unit)? = null,
) {
    var textInput by remember(convKey) { mutableStateOf("") }
    var showClearConfirm by remember(convKey) { mutableStateOf(false) }
    var pingSentNotification by remember(convKey) { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val peerContact = contacts.firstOrNull { it.nodeId == convKey }
    val isBroadcast = convKey == "^all"
    val isOnline = onlinePeers.containsKey(convKey)

    val title = if (isBroadcast) "Global Broadcast" else (peerContact?.announceName?.takeIf { it.isNotBlank() } ?: convKey)
    val subtitle = if (isBroadcast) {
        "Store-and-forward mesh flood over all radios & relays"
    } else if (isOnline) {
        "● Online nearby via physical link"
    } else {
        "Last seen recently • Store-and-forward routing"
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Modern Chat Header
        Surface(
            color = DtnColors.Card,
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Peer Avatar and Info
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Surface(
                            shape = CircleShape,
                            color = if (isBroadcast) DtnColors.GroupAvatar else DtnColors.avatarFor(convKey),
                            border = if (isOnline && !isBroadcast) BorderStroke(2.dp, DtnColors.Green) else null,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (isBroadcast) "📢" else (title.firstOrNull()?.uppercaseChar()?.toString() ?: "?"),
                                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                )
                            }
                        }
                        if (isOnline && !isBroadcast) {
                            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(DtnColors.Green).border(1.5.dp, Color.White, CircleShape))
                        }
                    }

                    Column {
                        Text(text = title, style = DtnType.RowTitle)
                        Text(text = subtitle, style = DtnType.Meta)
                    }
                }

                // Right: Actions (Encryption Tag, Ping Peer, Clear Chat)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isBroadcast && peerContact != null) {
                        Surface(
                            shape = DtnShapes.Pill,
                            color = if (peerContact.keyVerified) DtnColors.GreenSoft else DtnColors.OrangeSoft
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    if (peerContact.keyVerified) Icons.Filled.VerifiedUser else Icons.Filled.Shield,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = if (peerContact.keyVerified) DtnColors.GreenText else DtnColors.OrangeText
                                )
                                Text(
                                    text = if (peerContact.keyVerified) "X25519 E2EE" else "Unverified Key",
                                    style = TextStyle(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (peerContact.keyVerified) DtnColors.GreenText else DtnColors.OrangeText
                                    )
                                )
                            }
                        }
                    }

                    // Ping Peer Action Button
                    if (onPingPeer != null) {
                        OutlinedButton(
                            onClick = {
                                onPingPeer()
                                pingSentNotification = true
                            },
                            shape = DtnShapes.Button,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                if (pingSentNotification) Icons.Filled.Check else Icons.Filled.Bolt,
                                contentDescription = "Ping Peer",
                                modifier = Modifier.size(14.dp),
                                tint = if (pingSentNotification) DtnColors.GreenText else DtnColors.Primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (pingSentNotification) "Ping Sent" else "Ping",
                                style = TextStyle(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (pingSentNotification) DtnColors.GreenText else DtnColors.Primary
                                )
                            )
                        }
                    }

                    // Clear Chat Button
                    if (onClearChat != null && messages.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearConfirm = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                contentDescription = "Clear Chat",
                                tint = DtnColors.TextSubtitle,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = DtnColors.CardBorder)

        // Messages Stream with Date Dividers
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
            if (messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = CircleShape, color = DtnColors.PrimarySoft, modifier = Modifier.size(56.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = DtnColors.Primary, modifier = Modifier.size(28.dp))
                            }
                        }
                        Text("No messages in this channel yet", style = DtnType.RowTitle.copy(color = DtnColors.TextSubtitle))
                        Text("Type a message below to transmit across the store-and-forward mesh", style = DtnType.Meta)
                    }
                }
            } else {
                val cal = Calendar.getInstance()
                val todayStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                cal.add(Calendar.DATE, -1)
                val yesterdayStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(cal.time)

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    var prevDateKey = ""
                    messages.forEach { msg ->
                        val dateKey = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(msg.createdAtMs))
                        if (dateKey != prevDateKey) {
                            prevDateKey = dateKey
                            val label = when (dateKey) {
                                todayStr -> "Today"
                                yesterdayStr -> "Yesterday"
                                else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(msg.createdAtMs))
                            }
                            item(key = "divider_${msg.id}") {
                                DateDividerPill(label)
                            }
                        }
                        item(key = msg.id) {
                            ChatBubbleItem(msg = msg, onClick = { onMessageClick(msg) })
                        }
                    }
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = DtnColors.CardBorder)

        // Modern Bottom Message Composer & Quick Helpers
        Surface(
            color = DtnColors.Card,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Row of Quick Emojis & Quick Preset Action Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Quick Emojis
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val quickEmojis = listOf("👋", "👍", "❤️", "📍", "📡", "🤝", "✅", "⚠️")
                        quickEmojis.forEach { emoji ->
                            Surface(
                                shape = CircleShape,
                                color = DtnColors.Track,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .clickable { textInput += emoji }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(emoji, style = TextStyle(fontSize = 13.sp))
                                }
                            }
                        }
                    }

                    // Quick Preset Chips
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val quickPresets = listOf("👋 Hello!", "📡 Ping Mesh", "📍 Location Check", "✓ Received")
                        quickPresets.forEach { preset ->
                            Surface(
                                shape = DtnShapes.Pill,
                                color = DtnColors.Track,
                                border = BorderStroke(1.dp, DtnColors.CardBorder),
                                modifier = Modifier
                                    .height(24.dp)
                                    .clip(DtnShapes.Pill)
                                    .clickable {
                                        onSendMessage(preset)
                                    }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                ) {
                                    Text(
                                        text = preset,
                                        style = TextStyle(fontSize = 11.sp, color = DtnColors.TextSubtitle, fontWeight = FontWeight.Medium)
                                    )
                                }
                            }
                        }
                    }
                }

                // Main Input Row: Dynamic TextField + Byte Counter + Send Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, DtnColors.CardBorder),
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 44.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextField(
                                    value = textInput,
                                    onValueChange = { textInput = it },
                                    textStyle = TextStyle(fontSize = 14.sp, color = DtnColors.TextTitle, lineHeight = 20.sp),
                                    placeholder = {
                                        Text("Message $title (Enter to send, Shift+Enter for new line)...", style = TextStyle(fontSize = 13.sp, color = DtnColors.TextHint))
                                    },
                                    singleLine = false,
                                    maxLines = 4,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .onPreviewKeyEvent { keyEvent ->
                                            if (keyEvent.type == KeyEventType.KeyDown && (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter)) {
                                                if (keyEvent.isShiftPressed) {
                                                    false // Allow Shift+Enter to insert newline
                                                } else {
                                                    // Enter alone sends message and consumes event
                                                    if (textInput.isNotBlank()) {
                                                        onSendMessage(textInput.trim())
                                                        textInput = ""
                                                    }
                                                    true
                                                }
                                            } else {
                                                false
                                            }
                                        }
                                )

                                if (textInput.isNotBlank()) {
                                    IconButton(
                                        onClick = { textInput = "" },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = "Clear", tint = DtnColors.TextHint, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            // Sub-row: Byte budget and keyboard shortcut hints
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val byteCount = textInput.toByteArray(Charsets.UTF_8).size
                                val budgetMax = 223
                                Text(
                                    text = "Enter ↵ to send • Shift+Enter ↵ for new line",
                                    style = TextStyle(fontSize = 10.sp, color = DtnColors.TextHint)
                                )

                                Text(
                                    text = "$byteCount/$budgetMax B",
                                    style = TextStyle(
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        color = when {
                                            byteCount > budgetMax -> DtnColors.Orange
                                            byteCount > 180 -> DtnColors.OrangeText
                                            else -> DtnColors.TextHint
                                        }
                                    )
                                )
                            }
                        }
                    }

                    FilledIconButton(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                onSendMessage(textInput.trim())
                                textInput = ""
                            }
                        },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (textInput.isNotBlank()) DtnColors.Primary else DtnColors.Track,
                            contentColor = if (textInput.isNotBlank()) Color.White else DtnColors.TextHint
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Chat History?", style = DtnType.RowTitle) },
            text = { Text("Are you sure you want to delete all messages in this conversation from the local database?", style = DtnType.RowSubtitle) },
            confirmButton = {
                Button(
                    onClick = {
                        onClearChat?.invoke()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DtnColors.Red)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DateDividerPill(dateText: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = DtnShapes.Pill,
            color = DtnColors.Track,
            border = BorderStroke(1.dp, DtnColors.CardBorder)
        ) {
            Text(
                text = dateText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextSubtitle)
            )
        }
    }
}

@Composable
fun ChatBubbleItem(msg: ChatMessage, onClick: () -> Unit) {
    val isOut = msg.direction == "OUT"
    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.createdAtMs))

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalAlignment = if (isOut) Alignment.End else Alignment.Start
    ) {
        if (isOut) {
            // Outbound message bubble (Deep Indigo Gradient with nested status ticks)
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = 18.dp,
                    bottomEnd = 4.dp
                ),
                color = Color.Transparent,
                modifier = Modifier
                    .widthIn(min = 90.dp, max = 560.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF2563EB), Color(0xFF1D4ED8))
                        )
                    )
                    .clickable { onClick() }
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Text(
                        text = msg.text,
                        style = TextStyle(fontSize = 14.sp, color = Color.White, lineHeight = 20.sp)
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = timeStr,
                            style = TextStyle(fontSize = 10.sp, color = Color(0xFFBFDBFE))
                        )
                        val (statusTick, tickColor) = when (msg.status) {
                            "DELIVERED" -> "✓✓" to Color(0xFF4ADE80)
                            "SENT" -> "✓" to Color(0xFF93C5FD)
                            else -> "🕓" to Color(0xFFFCD34D)
                        }
                        Text(
                            text = statusTick,
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tickColor)
                        )
                    }
                }
            }
        } else {
            // Inbound message bubble (Pure White Card with Sender Pill)
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = 4.dp,
                    bottomEnd = 18.dp
                ),
                color = Color.White,
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shadowElevation = 1.dp,
                modifier = Modifier
                    .widthIn(min = 90.dp, max = 560.dp)
                    .clickable { onClick() }
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                    if (msg.peerNodeId != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(DtnColors.avatarFor(msg.peerNodeId)))
                            Text(
                                text = msg.peerNodeId,
                                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DtnColors.avatarFor(msg.peerNodeId))
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                    }
                    Text(
                        text = msg.text,
                        style = TextStyle(fontSize = 14.sp, color = DtnColors.TextTitle, lineHeight = 20.sp)
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = timeStr,
                            style = TextStyle(fontSize = 10.sp, color = DtnColors.TextHint)
                        )
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = "Inspect message",
                            modifier = Modifier.size(11.dp),
                            tint = DtnColors.TextHint.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

// ── NATIVE COMPOSE CANVAS 2D MESH TOPOLOGY GRAPH ───────────────────────────
@Composable
fun MeshTopologyCanvas(
    localNodeId: String,
    onlinePeers: Map<String, PeerEndpoint>,
    contacts: List<Contact>,
    selectedPeerId: String?,
    onSelectPeer: (String) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 28f,
        targetValue = 76f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart)
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart)
    )

    val peerList = remember(onlinePeers) { onlinePeers.values.toList() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0A0F1D))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(peerList) {
                    detectTapGestures { tapOffset ->
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val orbitRadius = (minOf(size.width, size.height) * 0.36f).coerceIn(90f, 130f)

                        // Check tap hit on orbiting peers
                        peerList.forEachIndexed { i, peer ->
                            val angle = (i * (2 * PI / peerList.size) - PI / 2).toFloat()
                            val peerX = centerX + orbitRadius * cos(angle)
                            val peerY = centerY + orbitRadius * sin(angle)
                            val dist = sqrt((tapOffset.x - peerX) * (tapOffset.x - peerX) + (tapOffset.y - peerY) * (tapOffset.y - peerY))
                            if (dist <= 30f) {
                                onSelectPeer(peer.nodeId)
                            }
                        }
                    }
                }
        ) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val orbitRadius = (minOf(size.width, size.height) * 0.36f).coerceIn(90f, 130f)

            // 1. Radar Grid Concentric Circles
            drawCircle(color = Color(0x1838BDF8), radius = orbitRadius * 0.4f, style = Stroke(width = 1f))
            drawCircle(color = Color(0x2238BDF8), radius = orbitRadius * 0.75f, style = Stroke(width = 1f))
            drawCircle(color = Color(0x2E38BDF8), radius = orbitRadius, style = Stroke(width = 1.2f))
            drawCircle(color = Color(0x1438BDF8), radius = orbitRadius * 1.25f, style = Stroke(width = 1f))

            // Crosshair lines
            drawLine(
                color = Color(0x1538BDF8),
                start = Offset(centerX - orbitRadius * 1.3f, centerY),
                end = Offset(centerX + orbitRadius * 1.3f, centerY),
                strokeWidth = 1f
            )
            drawLine(
                color = Color(0x1538BDF8),
                start = Offset(centerX, centerY - orbitRadius * 1.3f),
                end = Offset(centerX, centerY + orbitRadius * 1.3f),
                strokeWidth = 1f
            )

            // 2. Center Station Node (Pulsing halo + Center circle)
            drawCircle(
                color = Color(0xFF3B82F6).copy(alpha = pulseAlpha),
                radius = pulseRadius,
                center = Offset(centerX, centerY)
            )
            drawCircle(
                color = Color(0xFF2563EB),
                radius = 24f,
                center = Offset(centerX, centerY)
            )
            drawCircle(
                color = Color(0xFF60A5FA),
                radius = 16f,
                center = Offset(centerX, centerY)
            )
            drawCircle(
                color = Color.White,
                radius = 6f,
                center = Offset(centerX, centerY)
            )

            // 3. Orbiting Peers & Dynamic Connection Rays
            if (peerList.isEmpty()) {
                // Draw 4 default transport beacons orbiting
                val defaultBeacons = listOf(
                    "Local Wi-Fi" to Color(0xFF10B981),
                    "BLE Active" to Color(0xFF38BDF8),
                    "HiveMQ Relay" to Color(0xFFA855F7),
                    "USB LoRa" to Color(0xFFF59E0B)
                )
                defaultBeacons.forEachIndexed { i, beacon ->
                    val angle = (i * (2 * PI / defaultBeacons.size) - PI / 2).toFloat()
                    val beaconX = centerX + orbitRadius * cos(angle)
                    val beaconY = centerY + orbitRadius * sin(angle)

                    drawLine(
                        color = beacon.second.copy(alpha = 0.35f),
                        start = Offset(centerX, centerY),
                        end = Offset(beaconX, beaconY),
                        strokeWidth = 1.5f
                    )
                    drawCircle(
                        color = beacon.second.copy(alpha = 0.2f),
                        radius = 18f,
                        center = Offset(beaconX, beaconY)
                    )
                    drawCircle(
                        color = beacon.second,
                        radius = 8f,
                        center = Offset(beaconX, beaconY)
                    )
                }
            } else {
                peerList.forEachIndexed { i, peer ->
                    val angle = (i * (2 * PI / peerList.size) - PI / 2).toFloat()
                    val peerX = centerX + orbitRadius * cos(angle)
                    val peerY = centerY + orbitRadius * sin(angle)

                    val linkColor = when {
                        peer.linkType.contains("Wi-Fi", ignoreCase = true) -> Color(0xFF10B981)
                        peer.linkType.contains("BLE", ignoreCase = true) -> Color(0xFF38BDF8)
                        peer.linkType.contains("LoRa", ignoreCase = true) -> Color(0xFFF59E0B)
                        else -> Color(0xFFA855F7) // Relay
                    }

                    // Connecting ray with pulse
                    drawLine(
                        color = linkColor.copy(alpha = 0.75f),
                        start = Offset(centerX, centerY),
                        end = Offset(peerX, peerY),
                        strokeWidth = 2.5f
                    )

                    // Selection ring if active
                    if (selectedPeerId == peer.nodeId) {
                        drawCircle(
                            color = Color.White,
                            radius = 26f,
                            center = Offset(peerX, peerY),
                            style = Stroke(width = 2.5f)
                        )
                    }

                    // Peer Node Outer Glow
                    drawCircle(
                        color = linkColor.copy(alpha = 0.25f),
                        radius = 22f,
                        center = Offset(peerX, peerY)
                    )

                    // Peer Avatar Node
                    drawCircle(
                        color = DtnColors.avatarFor(peer.nodeId),
                        radius = 16f,
                        center = Offset(peerX, peerY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 5f,
                        center = Offset(peerX, peerY)
                    )
                }
            }
        }

        // Top Canvas Badge Overlay
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = DtnShapes.Pill, color = Color(0xFF0F172A).copy(alpha = 0.85f), border = BorderStroke(1.dp, Color(0xFF334155))) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF38BDF8)))
                    Text("Station $localNodeId (Center)", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8)))
                }
            }

            Surface(shape = DtnShapes.Pill, color = Color(0xFF0F172A).copy(alpha = 0.85f), border = BorderStroke(1.dp, Color(0xFF334155))) {
                Text(
                    text = if (peerList.isEmpty()) "Tap node to inspect" else "${peerList.size} Active Peer Links",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF94A3B8))
                )
            }
        }
    }
}

// ── MESSAGE DETAILS DIALOG / INSPECTOR ───────────────────────────────────────
@Composable
fun MessageDetailsDialog(
    msg: ChatMessage,
    orchestrator: DesktopOrchestrator,
    lifecycleLog: MessageLifecycleLog,
    contacts: List<Contact>,
    onClose: () -> Unit
) {
    val routes by lifecycleLog.routes.collectAsState()
    val entries by lifecycleLog.entries.collectAsState()

    val matchingRoute = routes.firstOrNull { it.msgId == msg.id || it.fullId == msg.id || it.msgId == msg.id.take(8) }
    val msgEntries = entries.filter { it.msgId == msg.id || msg.id.startsWith(it.msgId) || it.msgId == msg.id.take(8) }

    val isOut = msg.direction == "OUT"
    val contact = contacts.firstOrNull { it.nodeId == msg.convKey }
    val peerName = contact?.announceName?.takeIf { it.isNotBlank() } ?: msg.convKey
    val fullDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(msg.createdAtMs))

    var copiedText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.width(620.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = DtnColors.Primary)
                    Text("Message Journey & Security Profile", style = DtnType.PageTitle.copy(fontSize = 18.sp))
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Section 1: Core ID & Status Badge
                Card(
                    shape = DtnShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = DtnColors.Card),
                    border = BorderStroke(1.dp, DtnColors.CardBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("MESSAGE UUID", style = DtnType.SectionHeader)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        copyToClipboard(msg.id)
                                        copiedText = "UUID Copied"
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(copiedText ?: "Copy UUID", style = TextStyle(fontSize = 11.sp))
                                }
                            }
                        }

                        Text(text = msg.id, style = DtnType.Mono.copy(fontSize = 12.sp, color = DtnColors.Primary))

                        HorizontalDivider(color = DtnColors.CardBorder)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Direction & Scope", style = DtnType.Meta)
                            Text(
                                text = if (isOut) "Outbound → $peerName" else "Inbound ← ${msg.peerNodeId}",
                                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Status", style = DtnType.Meta)
                            val statusColor = when (msg.status) {
                                "DELIVERED" -> DtnColors.GreenText
                                "SENT" -> DtnColors.Primary
                                else -> DtnColors.OrangeText
                            }
                            Text(text = msg.status, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = statusColor))
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Timestamp", style = DtnType.Meta)
                            Text(text = fullDate, style = DtnType.Meta)
                        }
                    }
                }

                // Section 2: Cryptographic Profile
                Card(
                    shape = DtnShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = DtnColors.Card),
                    border = BorderStroke(1.dp, DtnColors.CardBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("CRYPTOGRAPHY & ROUTING STRATEGY", style = DtnType.SectionHeader)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Cipher Suite", style = DtnType.Meta)
                            val isDm = msg.convKey != "^all"
                            Text(
                                text = if (isDm) "X25519-ChaCha20Poly1305 (Suite 1)" else "Plain Broadcast (Suite 0)",
                                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (isDm) DtnColors.GreenText else DtnColors.TextTitle)
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Forwarding Strategy", style = DtnType.Meta)
                            val stratName = matchingRoute?.strategy ?: orchestrator.activeStrategy.value.name
                            Text(text = stratName, style = DtnType.Mono.copy(fontWeight = FontWeight.Bold, color = DtnColors.algorithm(stratName)))
                        }
                    }
                }

                // Section 3: Visual Route Journey Breadcrumb
                Card(
                    shape = DtnShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = DtnColors.Card),
                    border = BorderStroke(1.dp, DtnColors.CardBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("ROUTING JOURNEY & HOPS", style = DtnType.SectionHeader)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Origin
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(shape = CircleShape, color = DtnColors.PrimarySoft, modifier = Modifier.size(36.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(if (isOut) "Me" else "Phone", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DtnColors.Primary))
                                    }
                                }
                                Text(if (isOut) orchestrator.identity.nodeId.value.take(9) else msg.peerNodeId?.take(9) ?: "Origin", style = DtnType.Mono.copy(fontSize = 10.sp))
                            }

                            // Transit Arrow with Transport badge
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val transportUsed = matchingRoute?.lastPath?.takeIf { it.isNotBlank() } ?: "Wi-Fi Direct / BLE / Relay"
                                Surface(shape = DtnShapes.Pill, color = DtnColors.Track) {
                                    Text(text = transportUsed, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = TextStyle(fontSize = 10.sp, color = DtnColors.Primary))
                                }
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = DtnColors.TextHint, modifier = Modifier.size(18.dp))
                            }

                            // Destination
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(shape = CircleShape, color = if (msg.status == "DELIVERED") DtnColors.GreenSoft else DtnColors.OrangeSoft, modifier = Modifier.size(36.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(if (isOut) "Phone" else "Me", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (msg.status == "DELIVERED") DtnColors.GreenText else DtnColors.OrangeText))
                                    }
                                }
                                Text(if (isOut) msg.convKey.take(9) else orchestrator.identity.nodeId.value.take(9), style = DtnType.Mono.copy(fontSize = 10.sp))
                            }
                        }
                    }
                }

                // Section 4: Lifecycle Event History / Audit Trail
                Card(
                    shape = DtnShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = DtnColors.Card),
                    border = BorderStroke(1.dp, DtnColors.CardBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("TRANSIT AUDIT TRAIL", style = DtnType.SectionHeader)

                        if (msgEntries.isEmpty()) {
                            Text("● Recorded in database at $fullDate", style = DtnType.Meta)
                        } else {
                            msgEntries.forEach { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Surface(
                                            shape = DtnShapes.Pill,
                                            color = when (entry.event) {
                                                LifecycleEvent.CREATED -> DtnColors.PrimarySoft
                                                LifecycleEvent.BUFFERED -> DtnColors.OrangeSoft
                                                LifecycleEvent.FORWARDED, LifecycleEvent.FORWARDING -> Color(0xFFF3E8FF)
                                                LifecycleEvent.DELIVERED, LifecycleEvent.ACK_RECEIVED -> DtnColors.GreenSoft
                                                else -> DtnColors.GreySoft
                                            }
                                        ) {
                                            Text(
                                                text = entry.event.name,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            )
                                        }
                                        if (entry.extra.isNotBlank()) {
                                            Text(entry.extra, style = DtnType.Meta.copy(fontSize = 11.sp))
                                        }
                                    }
                                    Text(entry.timestamp, style = DtnType.Mono.copy(fontSize = 11.sp))
                                }
                            }
                        }
                    }
                }

                // Section 5: Payload Hex Viewer
                Card(
                    shape = DtnShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = DtnColors.Terminal),
                    border = BorderStroke(1.dp, DtnColors.CardBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("PAYLOAD RAW HEX BYTES (${msg.text.toByteArray(Charsets.UTF_8).size} BYTES)", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF94A3B8)))
                        Text(
                            text = msg.text.toByteArray(Charsets.UTF_8).toHexString(64),
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF38BDF8))
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = DtnColors.Primary),
                shape = DtnShapes.Button
            ) {
                Text("Dismiss")
            }
        }
    )
}

// ── RIGHT NODE INSPECTOR PANEL ───────────────────────────────────────────────
@Composable
fun NodeInspectorPanel(
    selectedConvKey: String,
    contacts: List<Contact>,
    onlinePeers: Map<String, Any>,
    orchestrator: DesktopOrchestrator
) {
    val contact = contacts.firstOrNull { it.nodeId == selectedConvKey }
    val isOnline = onlinePeers.containsKey(selectedConvKey)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("PEER INSPECTOR", style = DtnType.SectionHeader)

        if (selectedConvKey == "^all") {
            Card(
                shape = DtnShapes.Card,
                colors = CardDefaults.cardColors(containerColor = DtnColors.Background),
                border = BorderStroke(1.dp, DtnColors.CardBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Broadcast Domain", style = DtnType.RowTitle)
                    Text(
                        "Messages sent to ^all are unencrypted DTN flood bundles that propagate across all mesh nodes.",
                        style = DtnType.Meta
                    )
                }
            }
            return
        }

        // Profile Card
        Card(
            shape = DtnShapes.Card,
            colors = CardDefaults.cardColors(containerColor = DtnColors.Background),
            border = BorderStroke(1.dp, DtnColors.CardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = contact?.announceName ?: "Node Details", style = DtnType.RowTitle)
                Text(text = selectedConvKey, style = DtnType.Mono.copy(fontWeight = FontWeight.Bold, color = DtnColors.Primary))

                HorizontalDivider(color = DtnColors.CardBorder)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Status", style = DtnType.Meta)
                    Text(
                        text = if (isOnline) "● Online" else "Offline",
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isOnline) DtnColors.GreenText else DtnColors.Grey
                        )
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Key State", style = DtnType.Meta)
                    Text(
                        text = if (contact?.keyVerified == true) "Verified Ed25519" else "Unverified",
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (contact?.keyVerified == true) DtnColors.GreenText else DtnColors.OrangeText
                        )
                    )
                }
            }
        }

        // Routing & Link Diagnostics Card
        Card(
            shape = DtnShapes.Card,
            colors = CardDefaults.cardColors(containerColor = DtnColors.Background),
            border = BorderStroke(1.dp, DtnColors.CardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ROUTING PREDICTABILITY", style = DtnType.SectionHeader)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("RFC 6693 (P)", style = DtnType.Meta)
                    Text(text = "0.850", style = DtnType.Mono.copy(fontWeight = FontWeight.Bold))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Strategy", style = DtnType.Meta)
                    Text(text = orchestrator.activeStrategy.value.name, style = DtnType.Mono.copy(color = DtnColors.Primary))
                }
            }
        }

        Button(
            onClick = { orchestrator.broadcastAnnounce() },
            modifier = Modifier.fillMaxWidth().height(40.dp),
            shape = DtnShapes.Button,
            colors = ButtonDefaults.buttonColors(containerColor = DtnColors.PrimarySoft, contentColor = DtnColors.Primary)
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Send Route Probe", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

// ── RICH QUEUE INSPECTOR VIEW ───────────────────────────────────────────────
@Composable
fun QueueInspectorView(
    buffered: List<DtnMessage>,
    orchestrator: DesktopOrchestrator,
    contacts: List<Contact>
) {
    var searchQuery by remember { mutableStateOf("") }
    var expandedBundleId by remember { mutableStateOf<String?>(null) }

    val filtered = buffered.filter { bundle ->
        searchQuery.isBlank() ||
        bundle.id.contains(searchQuery, ignoreCase = true) ||
        bundle.destinationNodeId.value.contains(searchQuery, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Store-Carry-Forward Queue Inspector", style = DtnType.PageTitle)
                Text("${buffered.size} bundles held in memory awaiting next-hop encounters", style = DtnType.PageSubtitle)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { orchestrator.clearExpiredQueue() },
                    shape = DtnShapes.Button,
                    border = BorderStroke(1.dp, DtnColors.CardBorder)
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear Expired")
                }

                Button(
                    onClick = { orchestrator.flushQueueNow() },
                    colors = ButtonDefaults.buttonColors(containerColor = DtnColors.Primary),
                    shape = DtnShapes.Button
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Flush All Now")
                }
            }
        }

        // Search Bar
        Surface(
            shape = DtnShapes.Field,
            color = DtnColors.Card,
            border = BorderStroke(1.dp, DtnColors.CardBorder),
            modifier = Modifier.fillMaxWidth().height(44.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = DtnColors.TextHint, modifier = Modifier.size(18.dp))
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter queued bundles by ID or destination...", style = TextStyle(fontSize = 13.sp, color = DtnColors.TextHint)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Inbox, contentDescription = null, tint = DtnColors.TextHint, modifier = Modifier.size(48.dp))
                    Text("Buffer is clear", style = DtnType.RowTitle.copy(color = DtnColors.TextSubtitle))
                    Text("All store-carry-forward messages have been successfully relayed.", style = DtnType.Meta)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered) { bundle ->
                    val now = System.currentTimeMillis()
                    val elapsed = now - bundle.createdAtMs
                    val remainingMs = maxOf(0L, bundle.ttlMs - elapsed)
                    val ratio = (remainingMs.toFloat() / bundle.ttlMs.toFloat()).coerceIn(0f, 1f)

                    val ttlColor = when {
                        ratio > 0.5f -> DtnColors.Green
                        ratio > 0.2f -> DtnColors.Orange
                        else -> DtnColors.Red
                    }

                    val destContact = contacts.firstOrNull { it.nodeId == bundle.destinationNodeId.value }
                    val destName = destContact?.announceName?.takeIf { it.isNotBlank() } ?: bundle.destinationNodeId.value
                    val isExpanded = expandedBundleId == bundle.id

                    Card(
                        shape = DtnShapes.Card,
                        colors = CardDefaults.cardColors(containerColor = DtnColors.Card),
                        border = BorderStroke(1.dp, DtnColors.CardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("MSG: ${bundle.id.take(8)}", style = DtnType.Mono.copy(fontWeight = FontWeight.Bold, color = DtnColors.Primary))
                                    IconButton(
                                        onClick = { copyToClipboard(bundle.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy UUID", modifier = Modifier.size(13.dp), tint = DtnColors.TextHint)
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(shape = DtnShapes.Pill, color = DtnColors.Track) {
                                        Text(
                                            text = "Hop ${bundle.hopCount}",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        )
                                    }

                                    Surface(shape = DtnShapes.Pill, color = DtnColors.Track) {
                                        Text(
                                            text = "${bundle.payloadBytes.size} B",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        )
                                    }

                                    Button(
                                        onClick = { orchestrator.flushBundleNow(bundle) },
                                        colors = ButtonDefaults.buttonColors(containerColor = DtnColors.PrimarySoft, contentColor = DtnColors.Primary),
                                        shape = DtnShapes.Button,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Transmit Now", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold))
                                    }
                                }
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Destination: $destName (${bundle.destinationNodeId.value})", style = DtnType.Meta)
                                Text("${remainingMs / 60000}m remaining / ${bundle.ttlMs / 60000}m total", style = DtnType.Meta.copy(color = ttlColor, fontWeight = FontWeight.SemiBold))
                            }

                            // TTL Progress Bar
                            LinearProgressIndicator(
                                progress = { ratio },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = ttlColor,
                                trackColor = DtnColors.Track
                            )

                            // Expand Payload / Hex Button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isExpanded) "Hide Payload" else "Inspect Payload Hex",
                                    style = TextStyle(fontSize = 11.sp, color = DtnColors.Primary, fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.clickable {
                                        expandedBundleId = if (isExpanded) null else bundle.id
                                    }
                                )
                            }

                            if (isExpanded) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = DtnColors.Terminal,
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("HEX DUMP:", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFF94A3B8)))
                                        Text(
                                            text = bundle.payloadBytes.toHexString(48),
                                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF38BDF8))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── FULL EVENT LOG / JOURNAL VIEW ───────────────────────────────────────────
@Composable
fun JournalLogView(
    lifecycleLog: MessageLifecycleLog,
    onInspectMsgId: (String) -> Unit
) {
    val entries by lifecycleLog.entries.collectAsState()
    var selectedFilter by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }

    val filterOptions = listOf("ALL", "CREATED", "BUFFERED", "FORWARDED", "DELIVERED", "ACK_RECEIVED", "EXPIRED", "DROPPED")

    val filteredEntries = entries.filter { entry ->
        val matchesCategory = selectedFilter == "ALL" || entry.event.name == selectedFilter || (selectedFilter == "FORWARDED" && entry.event == LifecycleEvent.FORWARDING)
        val matchesSearch = searchQuery.isBlank() ||
                entry.msgId.contains(searchQuery, ignoreCase = true) ||
                entry.origin.contains(searchQuery, ignoreCase = true) ||
                entry.dest.contains(searchQuery, ignoreCase = true) ||
                entry.extra.contains(searchQuery, ignoreCase = true)
        matchesCategory && matchesSearch
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Message Lifecycle Journal", style = DtnType.PageTitle)
                Text("Complete immutable store-and-forward routing ledger (${entries.size} recorded events)", style = DtnType.PageSubtitle)
            }

            OutlinedButton(
                onClick = {
                    val export = entries.joinToString("\n") { "[${it.timestamp}] ${it.event} id=${it.msgId} ${it.origin}->${it.dest} ${it.extra}" }
                    copyToClipboard(export)
                },
                shape = DtnShapes.Button,
                border = BorderStroke(1.dp, DtnColors.CardBorder)
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Export Journal")
            }
        }

        // Filter Chips Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            filterOptions.forEach { filter ->
                val isSelected = selectedFilter == filter
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter, style = TextStyle(fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)) },
                    shape = DtnShapes.Pill
                )
            }
        }

        // Search Bar
        Surface(
            shape = DtnShapes.Field,
            color = DtnColors.Card,
            border = BorderStroke(1.dp, DtnColors.CardBorder),
            modifier = Modifier.fillMaxWidth().height(42.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = DtnColors.TextHint, modifier = Modifier.size(16.dp))
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter journal events by Message ID, Node, or Transport...", style = TextStyle(fontSize = 12.sp, color = DtnColors.TextHint)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (filteredEntries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matching journal events found.", style = DtnType.RowSubtitle)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredEntries) { entry ->
                    Card(
                        shape = DtnShapes.Card,
                        colors = CardDefaults.cardColors(containerColor = DtnColors.Card),
                        border = BorderStroke(1.dp, DtnColors.CardBorder),
                        modifier = Modifier.fillMaxWidth().clickable { onInspectMsgId(entry.msgId) }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Surface(
                                    shape = DtnShapes.Pill,
                                    color = when (entry.event) {
                                        LifecycleEvent.CREATED -> DtnColors.PrimarySoft
                                        LifecycleEvent.BUFFERED -> DtnColors.OrangeSoft
                                        LifecycleEvent.FORWARDED, LifecycleEvent.FORWARDING -> Color(0xFFF3E8FF)
                                        LifecycleEvent.DELIVERED, LifecycleEvent.ACK_RECEIVED -> DtnColors.GreenSoft
                                        LifecycleEvent.EXPIRED, LifecycleEvent.DROPPED -> DtnColors.RedSoft
                                        else -> DtnColors.GreySoft
                                    }
                                ) {
                                    Text(
                                        text = entry.event.name,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = TextStyle(
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = when (entry.event) {
                                                LifecycleEvent.CREATED -> DtnColors.Primary
                                                LifecycleEvent.BUFFERED -> DtnColors.OrangeText
                                                LifecycleEvent.FORWARDED, LifecycleEvent.FORWARDING -> Color(0xFF9333EA)
                                                LifecycleEvent.DELIVERED, LifecycleEvent.ACK_RECEIVED -> DtnColors.GreenText
                                                LifecycleEvent.EXPIRED, LifecycleEvent.DROPPED -> DtnColors.RedText
                                                else -> DtnColors.TextSubtitle
                                            }
                                        )
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("MSG: ${entry.msgId}", style = DtnType.Mono.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp))
                                        Text("·", style = DtnType.Meta)
                                        Text("${entry.origin} → ${entry.dest}", style = DtnType.Mono.copy(fontSize = 11.sp, color = DtnColors.Primary))
                                    }
                                    if (entry.extra.isNotBlank()) {
                                        Text(entry.extra, style = DtnType.Meta.copy(fontSize = 11.sp))
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(entry.timestamp, style = DtnType.Mono.copy(fontSize = 12.sp, color = DtnColors.TextHint))
                                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = DtnColors.TextHint, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── ADVANCED NETWORK TOPOLOGY VIEW WITH 2D CANVAS GRAPH ─────────────────────
@Composable
fun NetworkTopologyView(
    orchestrator: DesktopOrchestrator,
    onlinePeers: Map<String, PeerEndpoint>,
    contacts: List<Contact>,
    onOpenChat: (String) -> Unit
) {
    var phoneIpInput by remember { mutableStateOf("") }
    var connectStatus by remember { mutableStateOf<String?>(null) }
    var selectedGraphPeerId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val localIps = remember { orchestrator.getLocalIpAddresses() }
    val isSweeping by orchestrator.transportManager.wifiDirect.isSweeping.collectAsState()
    val bleAvailable by orchestrator.transportManager.ble.isBleAvailable.collectAsState()
    val bleDiscovered by orchestrator.transportManager.ble.discoveredPeers.collectAsState()
    val wifiConnectedPeers by orchestrator.transportManager.wifiDirect.connectedPeers.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Network Links & Discovered Nodes", style = DtnType.PageTitle)
        Text("Physical mesh channels, direct IP links, and live 2D topology graph", style = DtnType.PageSubtitle)

        // 4 Physical Transport Overview Cards
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LinkCard("USB Serial LoRa", "COM Port 115200 baud", orchestrator.transportManager.usbSerial.isConnected.value, Modifier.weight(1f))
            LinkCard("Local Wi-Fi", "TCP/UDP Port 9734", orchestrator.transportManager.wifiDirect.isListening.value, Modifier.weight(1f))
            LinkCard("Bluetooth LE", "GATT Server & Active Central", orchestrator.transportManager.ble.isScanning.value, Modifier.weight(1f))
            LinkCard("Cloud Relay", "HiveMQ MQTT + Cloudflare", orchestrator.transportManager.internet.isConnected.value, Modifier.weight(1f))
        }

        // ── 2D MESH TOPOLOGY CANVAS GRAPH ────────────────────────────────────
        Card(
            shape = DtnShapes.Card,
            colors = CardDefaults.cardColors(containerColor = DtnColors.Card),
            border = BorderStroke(1.dp, DtnColors.CardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Hub, contentDescription = null, tint = DtnColors.Primary)
                        Text("Live 2D Mesh Topology Canvas", style = DtnType.RowTitle)
                    }

                    Surface(shape = DtnShapes.Pill, color = DtnColors.PrimarySoft) {
                        Text(
                            text = "${onlinePeers.size} Discovered Links",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DtnColors.Primary)
                        )
                    }
                }

                Text(
                    "Interactive real-time radar mesh visualization. Nodes orbit the central command station according to active transport rays (Emerald = Wi-Fi :9734, Sky Blue = BLE GATT, Purple = Cloud Relay, Amber = USB LoRa). Tap any node to inspect.",
                    style = DtnType.Meta
                )

                MeshTopologyCanvas(
                    localNodeId = orchestrator.identity.nodeId.value,
                    onlinePeers = onlinePeers,
                    contacts = contacts,
                    selectedPeerId = selectedGraphPeerId,
                    onSelectPeer = { selectedGraphPeerId = it }
                )

                // Selected Graph Peer Inspector Banner
                if (selectedGraphPeerId != null) {
                    val peer = onlinePeers[selectedGraphPeerId]
                    val contact = contacts.firstOrNull { it.nodeId == selectedGraphPeerId }
                    val name = contact?.announceName?.takeIf { it.isNotBlank() } ?: selectedGraphPeerId!!

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = DtnColors.Track,
                        border = BorderStroke(1.dp, DtnColors.Primary.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(name, style = DtnType.RowTitle.copy(fontSize = 14.sp))
                                    Surface(shape = DtnShapes.Pill, color = DtnColors.GreenSoft) {
                                        Text(peer?.linkType ?: "Active Link", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = TextStyle(fontSize = 10.sp, color = DtnColors.GreenText, fontWeight = FontWeight.Bold))
                                    }
                                }
                                Text("Node ID: ${selectedGraphPeerId!!} · PRoPHET Predictability: 0.850", style = DtnType.Meta)
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { orchestrator.sendAnnounceTo(NodeId(selectedGraphPeerId!!), wantsReply = true) },
                                    shape = DtnShapes.Button
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Ping Node", style = TextStyle(fontSize = 11.sp))
                                }

                                Button(
                                    onClick = { onOpenChat(selectedGraphPeerId!!) },
                                    colors = ButtonDefaults.buttonColors(containerColor = DtnColors.Primary),
                                    shape = DtnShapes.Button
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Open Chat", style = TextStyle(fontSize = 11.sp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── WI-FI DIRECT / LOCAL PEER CONNECT CARD ────────────────────────────
        Card(
            shape = DtnShapes.Card,
            colors = CardDefaults.cardColors(containerColor = DtnColors.Card),
            border = BorderStroke(1.dp, DtnColors.CardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, tint = DtnColors.Primary)
                        Text("Wi-Fi Direct / Local LAN Direct Link", style = DtnType.RowTitle)
                    }

                    if (localIps.isNotEmpty()) {
                        Surface(shape = DtnShapes.Pill, color = DtnColors.Track) {
                            Text(
                                text = "Station IP: ${localIps.first()}",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                Text(
                    "Connect directly to your mobile phone over local Wi-Fi or Wi-Fi Direct SoftAP. Both devices listen on TCP port 9734 for instant zero-invitation bundle transfer.",
                    style = DtnType.Meta
                )

                // Direct IP Input Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = phoneIpInput,
                        onValueChange = { phoneIpInput = it.trim() },
                        placeholder = { Text("Enter Phone IP (e.g. 192.168.49.1 or 192.168.1.xxx)", style = TextStyle(fontSize = 13.sp)) },
                        shape = DtnShapes.Field,
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = {
                            if (phoneIpInput.isNotBlank()) {
                                connectStatus = "Connecting to $phoneIpInput:9734..."
                                scope.launch {
                                    val ok = orchestrator.connectToWifiPeer(phoneIpInput)
                                    connectStatus = if (ok) "✓ Successfully linked with $phoneIpInput!" else "✕ Could not connect to $phoneIpInput:9734 (Check Wi-Fi network)"
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DtnColors.Primary),
                        shape = DtnShapes.Button,
                        modifier = Modifier.height(50.dp)
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Connect Now")
                    }

                    OutlinedButton(
                        onClick = { orchestrator.triggerSubnetSweep() },
                        shape = DtnShapes.Button,
                        border = BorderStroke(1.dp, DtnColors.CardBorder),
                        modifier = Modifier.height(50.dp)
                    ) {
                        Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isSweeping) "Scanning Subnet..." else "Scan Subnet (/24)")
                    }
                }

                // Quick Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Quick Presets:", style = DtnType.Meta)
                    AssistChip(
                        onClick = { phoneIpInput = "192.168.49.1" },
                        label = { Text("Android Wi-Fi Direct GO (192.168.49.1)", fontSize = 11.sp) }
                    )
                    AssistChip(
                        onClick = { phoneIpInput = "192.168.43.1" },
                        label = { Text("Android Hotspot (192.168.43.1)", fontSize = 11.sp) }
                    )
                }

                if (connectStatus != null) {
                    Text(
                        text = connectStatus!!,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (connectStatus!!.startsWith("✓")) DtnColors.GreenText else DtnColors.OrangeText
                        )
                    )
                }

                if (wifiConnectedPeers.isNotEmpty()) {
                    HorizontalDivider(color = DtnColors.CardBorder)
                    Text("CONNECTED WI-FI PEERS (${wifiConnectedPeers.size})", style = DtnType.SectionHeader)
                    wifiConnectedPeers.forEach { (peerId, ip) ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(peerId, style = DtnType.Mono.copy(fontWeight = FontWeight.Bold, color = DtnColors.Primary))
                            Text("Reachable at $ip:9734", style = DtnType.Meta)
                        }
                    }
                }
            }
        }

        // ── BLE HARDWARE CONTROL CARD ─────────────────────────────────────────
        Card(
            shape = DtnShapes.Card,
            colors = CardDefaults.cardColors(containerColor = DtnColors.Card),
            border = BorderStroke(1.dp, DtnColors.CardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = Color(0xFF3B82F6))
                        Text("Bluetooth Low Energy (BLE) Native Bridge", style = DtnType.RowTitle)
                    }

                    OutlinedButton(
                        onClick = { orchestrator.restartBle() },
                        shape = DtnShapes.Button,
                        border = BorderStroke(1.dp, DtnColors.CardBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Restart BLE Worker", style = TextStyle(fontSize = 11.sp))
                    }
                }

                Text(
                    "Native WinRT BLE Bridge worker hosting GATT Service d791b000-2c8f-4f2a-9b1e-000000000001 with active central connection and chunked wire framing.",
                    style = DtnType.Meta
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (bleAvailable) DtnColors.Green else DtnColors.Red))
                        Text(if (bleAvailable) "WinRT GATT Service Active" else "BLE Unavailable", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(DtnColors.Green))
                        Text("Active Central Scanner Scanning", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold))
                    }

                    Text("Discovered BLE Peers: ${bleDiscovered.size}", style = DtnType.Meta)
                }

                if (bleDiscovered.isNotEmpty()) {
                    HorizontalDivider(color = DtnColors.CardBorder)
                    bleDiscovered.forEach { (peerId, mac) ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(peerId, style = DtnType.Mono.copy(fontWeight = FontWeight.Bold, color = DtnColors.Primary))
                            Text("MAC: $mac", style = DtnType.Meta)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("ALL DISCOVERED PEERS (${onlinePeers.size})", style = DtnType.SectionHeader)

        if (onlinePeers.isEmpty()) {
            Text("No external peers heard yet. Looking on BLE, Local Wi-Fi, and Internet Relay...", style = DtnType.Meta)
        } else {
            onlinePeers.keys.forEach { peerId ->
                val contact = contacts.firstOrNull { it.nodeId == peerId }
                Card(
                    shape = DtnShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = DtnColors.Card),
                    border = BorderStroke(1.dp, DtnColors.CardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(shape = CircleShape, color = DtnColors.avatarFor(peerId), modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(contact?.announceName?.firstOrNull()?.uppercaseChar()?.toString() ?: "?", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(contact?.announceName ?: peerId, style = DtnType.RowTitle)
                            Text(peerId, style = DtnType.Mono.copy(color = DtnColors.Primary))
                        }
                        Surface(shape = DtnShapes.Pill, color = DtnColors.GreenSoft) {
                            Text("Online Nearby", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = TextStyle(fontSize = 12.sp, color = DtnColors.GreenText, fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LinkCard(title: String, desc: String, isOnline: Boolean, modifier: Modifier) {
    Card(
        shape = DtnShapes.Card,
        colors = CardDefaults.cardColors(containerColor = DtnColors.Card),
        border = BorderStroke(1.dp, DtnColors.CardBorder),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = DtnType.RowTitle.copy(fontSize = 14.sp))
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (isOnline) DtnColors.Green else DtnColors.Grey))
            }
            Text(desc, style = DtnType.Meta.copy(fontSize = 11.sp))
            Text(if (isOnline) "● Active" else "Disconnected", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isOnline) DtnColors.GreenText else DtnColors.TextSubtitle))
        }
    }
}

// ── FILTERABLE CONSOLE LOG VIEW ──────────────────────────────────────────────
@Composable
fun ConsoleLogView(lifecycleLog: MessageLifecycleLog) {
    val logs by lifecycleLog.console.collectAsState()
    var selectedTag by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }

    val tags = listOf("ALL", "RELAY", "BLE", "WIFI", "CRYPTO", "ROUTER", "ORCHESTRATOR")

    val filteredLogs = logs.filter { line ->
        val matchesTag = when (selectedTag) {
            "ALL" -> true
            "RELAY" -> line.contains("[InternetRelay]") || line.contains("[MQTT]")
            "BLE" -> line.contains("[WindowsBle]") || line.contains("[BLE]")
            "WIFI" -> line.contains("[WifiDirect]") || line.contains("[LAN]")
            "CRYPTO" -> line.contains("[Crypto]") || line.contains("[Envelope]") || line.contains("[Ed25519]")
            "ROUTER" -> line.contains("[Router]") || line.contains("[Routing]")
            "ORCHESTRATOR" -> line.contains("[Orchestrator]")
            else -> true
        }
        val matchesSearch = searchQuery.isBlank() || line.contains(searchQuery, ignoreCase = true)
        matchesTag && matchesSearch
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("System Diagnostics & Transport Console", style = DtnType.PageTitle)
                Text("Real-time subsystem logs and event stream", style = DtnType.PageSubtitle)
            }

            OutlinedButton(
                onClick = {
                    val all = filteredLogs.joinToString("\n")
                    copyToClipboard(all)
                },
                shape = DtnShapes.Button,
                border = BorderStroke(1.dp, DtnColors.CardBorder)
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy Output")
            }
        }

        // Tag Filters & Search Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tags.forEach { tag ->
                val isSelected = selectedTag == tag
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedTag = tag },
                    label = { Text(tag, style = TextStyle(fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)) },
                    shape = DtnShapes.Pill
                )
            }
        }

        Surface(
            shape = DtnShapes.Field,
            color = DtnColors.Card,
            border = BorderStroke(1.dp, DtnColors.CardBorder),
            modifier = Modifier.fillMaxWidth().height(40.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = DtnColors.TextHint, modifier = Modifier.size(15.dp))
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter console output...", style = TextStyle(fontSize = 12.sp, color = DtnColors.TextHint)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Surface(
            shape = DtnShapes.Card,
            color = DtnColors.Terminal,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            if (filteredLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No log messages matching filter.", style = TextStyle(fontFamily = FontFamily.Monospace, color = Color(0xFF64748B)))
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filteredLogs) { log ->
                        Text(
                            text = log,
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF38BDF8))
                        )
                    }
                }
            }
        }
    }
}

// ── ADD CONTACT MODAL ────────────────────────────────────────────────────────
@Composable
fun AddContactModal(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var nodeIdInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Contact Manually", style = DtnType.PageTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter the 8-hex digit node ID from the mobile phone:", style = DtnType.Meta)
                OutlinedTextField(
                    value = nodeIdInput,
                    onValueChange = { nodeIdInput = it.trim() },
                    label = { Text("Node ID (e.g. !7e0e6b6e)") },
                    shape = DtnShapes.Field,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Display Name (e.g. My Phone)") },
                    shape = DtnShapes.Field,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val formatted = if (nodeIdInput.startsWith("!")) nodeIdInput else "!$nodeIdInput"
                    if (formatted.length >= 9) {
                        onAdd(formatted, nameInput.ifBlank { formatted })
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DtnColors.Primary),
                shape = DtnShapes.Button
            ) {
                Text("Add Contact")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
