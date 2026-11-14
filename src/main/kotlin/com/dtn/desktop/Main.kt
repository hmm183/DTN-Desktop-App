package com.dtn.desktop

import androidx.compose.animation.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
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
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class DesktopNavTab(val label: String, val icon: ImageVector) {
    CHATS("Chats", Icons.AutoMirrored.Filled.Chat),
    QUEUE("Queue", Icons.Filled.Inbox),
    NETWORK("Network", Icons.Filled.Hub),
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
            onSelectStrategy = { orchestrator.setStrategy(it) },
            onAnnounce = { orchestrator.broadcastAnnounce() }
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
                            contacts = contacts
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
    onAnnounce: () -> Unit,
) {
    val usbSerialConnected by orchestrator.transportManager.usbSerial.isConnected.collectAsState()
    val bleScanning by orchestrator.transportManager.ble.isScanning.collectAsState()
    val wifiListening by orchestrator.transportManager.wifiDirect.isListening.collectAsState()
    val internetConnected by orchestrator.transportManager.internet.isConnected.collectAsState()

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

            // Right: Strategy Dropdown + Announce Button
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StrategySelectorPill(activeStrategy, onSelectStrategy)

                Button(
                    onClick = onAnnounce,
                    colors = ButtonDefaults.buttonColors(containerColor = DtnColors.Primary),
                    shape = DtnShapes.Button,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Filled.Podcasts, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Announce", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
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
        // Navigation Tabs (5 tabs Segmented bar)
        Surface(
            shape = DtnShapes.Button,
            color = DtnColors.Track,
            modifier = Modifier.fillMaxWidth().height(42.dp)
        ) {
            Row(modifier = Modifier.fillMaxSize().padding(2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                DesktopNavTab.entries.forEach { tab ->
                    val isSelected = activeTab == tab
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxHeight().clickable { onTabSelected(tab) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) DtnColors.Card else Color.Transparent
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
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
            // Avatar Circle
            Box(contentAlignment = Alignment.BottomEnd) {
                Surface(
                    shape = CircleShape,
                    color = avatarColor,
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

// ── CENTER CHAT VIEW ─────────────────────────────────────────────────────────
@Composable
fun ChatView(
    convKey: String,
    messages: List<ChatMessage>,
    contacts: List<Contact>,
    onlinePeers: Map<String, Any>,
    onSendMessage: (String) -> Unit,
    onMessageClick: (ChatMessage) -> Unit,
) {
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val peerContact = contacts.firstOrNull { it.nodeId == convKey }
    val isBroadcast = convKey == "^all"
    val isOnline = onlinePeers.containsKey(convKey)

    val title = if (isBroadcast) "Global Broadcast" else (peerContact?.announceName?.takeIf { it.isNotBlank() } ?: convKey)
    val subtitle = if (isBroadcast) "Store-and-forward mesh broadcast across all radios & relays" else if (isOnline) "● Online nearby" else "Last seen recently"

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Chat Header
        Surface(
            color = DtnColors.Card,
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = if (isBroadcast) DtnColors.GroupAvatar else DtnColors.avatarFor(convKey),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = if (isBroadcast) "📢" else (title.firstOrNull()?.uppercaseChar()?.toString() ?: "?"),
                                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            )
                        }
                    }

                    Column {
                        Text(text = title, style = DtnType.RowTitle)
                        Text(text = subtitle, style = DtnType.Meta)
                    }
                }

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
            }
        }

        HorizontalDivider(thickness = 1.dp, color = DtnColors.CardBorder)

        // Messages Stream
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            if (messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = CircleShape, color = DtnColors.PrimarySoft, modifier = Modifier.size(56.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = DtnColors.Primary, modifier = Modifier.size(28.dp))
                            }
                        }
                        Text("No messages yet", style = DtnType.RowTitle.copy(color = DtnColors.TextSubtitle))
                        Text("Send a message over DTN store-and-forward mesh", style = DtnType.Meta)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages) { msg ->
                        ChatBubbleItem(msg = msg, onClick = { onMessageClick(msg) })
                    }
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = DtnColors.CardBorder)

        // Bottom Message Composer
        Surface(
            color = DtnColors.Card,
            modifier = Modifier.fillMaxWidth().height(68.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = DtnShapes.Field,
                    color = DtnColors.Track,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    TextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        textStyle = TextStyle(fontSize = 14.sp, color = DtnColors.TextTitle),
                        placeholder = { Text("Type message... (Press Enter to send)", style = TextStyle(fontSize = 14.sp, color = DtnColors.TextHint)) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxSize().onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter) {
                                if (textInput.isNotBlank()) {
                                    onSendMessage(textInput.trim())
                                    textInput = ""
                                }
                                true
                            } else false
                        }
                    )
                }

                FilledIconButton(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            onSendMessage(textInput.trim())
                            textInput = ""
                        }
                    },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = DtnColors.Primary),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun ChatBubbleItem(msg: ChatMessage, onClick: () -> Unit) {
    val isOut = msg.direction == "OUT"
    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.createdAtMs))

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOut) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isOut) 16.dp else 4.dp,
                bottomEnd = if (isOut) 4.dp else 16.dp
            ),
            color = if (isOut) DtnColors.BubbleOut else DtnColors.BubbleIn,
            modifier = Modifier.widthIn(max = 540.dp).clickable { onClick() }
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isOut && msg.peerNodeId != null) {
                        Text(
                            text = msg.peerNodeId,
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DtnColors.Primary)
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    // Clickable info icon cue
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = "Inspect message",
                        modifier = Modifier.size(13.dp),
                        tint = if (isOut) Color.White.copy(alpha = 0.7f) else DtnColors.TextHint
                    )
                }

                Spacer(Modifier.height(2.dp))

                Text(
                    text = msg.text,
                    style = TextStyle(fontSize = 14.sp, color = if (isOut) Color.White else DtnColors.TextTitle)
                )
            }
        }

        Spacer(Modifier.height(3.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Text(text = timeStr, style = TextStyle(fontSize = 11.sp, color = DtnColors.TextHint))
            if (isOut) {
                val statusText = when (msg.status) {
                    "DELIVERED" -> "✓✓ Delivered"
                    "SENT" -> "✓ Sent"
                    else -> "🕓 Queued"
                }
                val statusColor = when (msg.status) {
                    "DELIVERED" -> DtnColors.GreenText
                    "SENT" -> DtnColors.Primary
                    else -> DtnColors.OrangeText
                }
                Text(text = "· $statusText", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = statusColor))
            }
            Text(
                text = "· Click to inspect",
                style = TextStyle(fontSize = 10.sp, color = DtnColors.TextHint, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            )
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
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
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
                    Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
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

// ── UPGRADED NETWORK TOPOLOGY & WI-FI / BLE VIEW ─────────────────────────────
@Composable
fun NetworkTopologyView(
    orchestrator: DesktopOrchestrator,
    onlinePeers: Map<String, Any>,
    contacts: List<Contact>
) {
    var phoneIpInput by remember { mutableStateOf("") }
    var connectStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val localIps = remember { orchestrator.getLocalIpAddresses() }
    val isSweeping by orchestrator.transportManager.wifiDirect.isSweeping.collectAsState()
    val bleAvailable by orchestrator.transportManager.ble.isBleAvailable.collectAsState()
    val bleDiscovered by orchestrator.transportManager.ble.discoveredPeers.collectAsState()
    val wifiConnectedPeers by orchestrator.transportManager.wifiDirect.connectedPeers.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Network Links & Discovered Nodes", style = DtnType.PageTitle)
        Text("Physical mesh channels, direct IP links, and peer discovery status", style = DtnType.PageSubtitle)

        // 4 Physical Transport Overview Cards
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LinkCard("USB Serial LoRa", "COM Port 115200 baud", orchestrator.transportManager.usbSerial.isConnected.value, Modifier.weight(1f))
            LinkCard("Local Wi-Fi", "TCP/UDP Port 9734", orchestrator.transportManager.wifiDirect.isListening.value, Modifier.weight(1f))
            LinkCard("Bluetooth LE", "GATT Server & Active Central", orchestrator.transportManager.ble.isScanning.value, Modifier.weight(1f))
            LinkCard("Cloud Relay", "HiveMQ MQTT + Cloudflare", orchestrator.transportManager.internet.isConnected.value, Modifier.weight(1f))
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
