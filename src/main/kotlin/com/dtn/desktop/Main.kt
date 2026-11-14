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
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.routing.StrategySelector.StrategyType
import com.dtn.mesh.service.DesktopOrchestrator
import com.dtn.mesh.service.MessageLifecycleLog
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class DesktopNavTab(val label: String, val icon: ImageVector) {
    CHATS("Chats", Icons.AutoMirrored.Filled.Chat),
    QUEUE("Queue", Icons.Filled.Inbox),
    NETWORK("Network", Icons.Filled.Hub),
    CONSOLE("Console", Icons.Filled.Terminal)
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
            size = DpSize(1260.dp, 840.dp)
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
    val chatMessages by database.chatMessages.collectAsState()
    val contacts by database.contacts.collectAsState()
    val bufferedMessages by database.bufferedMessages.collectAsState()
    val onlinePeers by orchestrator.transportManager.onlinePeers.collectAsState()
    val activeStrategy by orchestrator.activeStrategy.collectAsState()

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
            // LEFT COLUMN (320.dp): Tabs & Peer List
            Surface(
                modifier = Modifier.width(320.dp).fillMaxHeight(),
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
                        QueueInspectorView(bufferedMessages, onFlushNow = { orchestrator.flushQueueNow() })
                    }
                    DesktopNavTab.NETWORK -> {
                        NetworkTopologyView(orchestrator, onlinePeers, contacts)
                    }
                    DesktopNavTab.CONSOLE -> {
                        ConsoleLogView(lifecycleLog)
                    }
                }
            }
        }
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

            // Center: 4 Live Transport Pills
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
                    name = "BLE",
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
    onAddContact: (String, String) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // Navigation Tabs (Segmented bar)
        Surface(
            shape = DtnShapes.Button,
            color = DtnColors.Track,
            modifier = Modifier.fillMaxWidth().height(40.dp)
        ) {
            Row(modifier = Modifier.fillMaxSize().padding(3.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                DesktopNavTab.entries.forEach { tab ->
                    val isSelected = activeTab == tab
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxHeight().clickable { onTabSelected(tab) },
                        shape = RoundedCornerShape(9.dp),
                        color = if (isSelected) DtnColors.Card else Color.Transparent
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = if (isSelected) DtnColors.Primary else DtnColors.TextSubtitle
                                )
                                Text(
                                    text = tab.label,
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (isSelected) DtnColors.Primary else DtnColors.TextSubtitle
                                    )
                                )
                                if (tab == DesktopNavTab.QUEUE && bufferedCount > 0) {
                                    Surface(shape = CircleShape, color = DtnColors.Orange) {
                                        Text(
                                            text = "$bufferedCount",
                                            modifier = Modifier.padding(horizontal = 4.dp),
                                            style = TextStyle(fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Search Bar
        Surface(
            shape = DtnShapes.Pill,
            color = DtnColors.Track,
            modifier = Modifier.fillMaxWidth().height(40.dp)
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
                    textStyle = TextStyle(fontSize = 13.sp, color = DtnColors.TextTitle),
                    placeholder = { Text("Search contacts...", style = TextStyle(fontSize = 13.sp, color = DtnColors.TextHint)) },
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

        Spacer(Modifier.height(12.dp))

        // Conversations List
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Pinned Broadcast Channel
            item {
                ConversationRowItem(
                    title = "Broadcast Channel",
                    subtitle = "Public mesh broadcast",
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
            modifier = Modifier.fillMaxWidth().height(42.dp),
            shape = DtnShapes.Button,
            border = BorderStroke(1.dp, DtnColors.CardBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = DtnColors.Primary)
        ) {
            Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add Contact Manually", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
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
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = initial,
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
                        fontSize = 14.sp,
                        color = if (isSelected) DtnColors.Primary else DtnColors.TextTitle
                    ),
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    style = DtnType.RowSubtitle.copy(fontSize = 12.sp),
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
) {
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val peerContact = contacts.firstOrNull { it.nodeId == convKey }
    val isBroadcast = convKey == "^all"
    val isOnline = onlinePeers.containsKey(convKey)

    val title = if (isBroadcast) "Global Broadcast" else (peerContact?.announceName?.takeIf { it.isNotBlank() } ?: convKey)
    val subtitle = if (isBroadcast) "Every node within radio & relay range receives this" else if (isOnline) "● Online nearby" else "Last seen recently"

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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { msg ->
                        ChatBubbleItem(msg)
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
fun ChatBubbleItem(msg: ChatMessage) {
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
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                if (!isOut && msg.peerNodeId != null) {
                    Text(
                        text = msg.peerNodeId,
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DtnColors.Primary)
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    text = msg.text,
                    style = TextStyle(fontSize = 14.sp, color = if (isOut) Color.White else DtnColors.TextTitle)
                )
            }
        }

        Spacer(Modifier.height(2.dp))

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
        }
    }
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

// ── QUEUE, NETWORK & CONSOLE VIEWS ───────────────────────────────────────────
@Composable
fun QueueInspectorView(buffered: List<com.dtn.mesh.model.DtnMessage>, onFlushNow: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Store-Carry-Forward Queue", style = DtnType.PageTitle)
                Text("${buffered.size} bundles waiting for next-hop encounters", style = DtnType.PageSubtitle)
            }
            Button(
                onClick = onFlushNow,
                colors = ButtonDefaults.buttonColors(containerColor = DtnColors.Primary),
                shape = DtnShapes.Button
            ) {
                Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Flush Queue")
            }
        }

        if (buffered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Buffer is empty — all messages delivered!", style = DtnType.RowSubtitle)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(buffered) { bundle ->
                    Card(
                        shape = DtnShapes.Card,
                        colors = CardDefaults.cardColors(containerColor = DtnColors.Card),
                        border = BorderStroke(1.dp, DtnColors.CardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("MSG: ${bundle.id.take(8)}...", style = DtnType.Mono.copy(fontWeight = FontWeight.Bold))
                                Text("Destination: ${bundle.destinationNodeId.value}", style = DtnType.Meta)
                            }
                            Surface(shape = DtnShapes.Pill, color = DtnColors.OrangeSoft) {
                                Text(
                                    text = "TTL: ${bundle.ttlMs / 60000}m",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = TextStyle(fontSize = 11.sp, color = DtnColors.OrangeText, fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkTopologyView(
    orchestrator: DesktopOrchestrator,
    onlinePeers: Map<String, Any>,
    contacts: List<Contact>
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Network Links & Discovered Nodes", style = DtnType.PageTitle)
        Text("Active physical channels and peers in range", style = DtnType.PageSubtitle)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LinkCard("USB Serial LoRa", "COM Port 115200 baud", orchestrator.transportManager.usbSerial.isConnected.value, Modifier.weight(1f))
            LinkCard("Local Wi-Fi", "TCP/UDP Port 9734", orchestrator.transportManager.wifiDirect.isListening.value, Modifier.weight(1f))
            LinkCard("Bluetooth LE", "GATT Service d791b000", orchestrator.transportManager.ble.isScanning.value, Modifier.weight(1f))
            LinkCard("Cloud Relay", "HiveMQ MQTT + Cloudflare", orchestrator.transportManager.internet.isConnected.value, Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))
        Text("DISCOVERED PEERS (${onlinePeers.size})", style = DtnType.SectionHeader)

        if (onlinePeers.isEmpty()) {
            Text("No external peers heard yet. Looking on BLE, Local Wi-Fi, and Internet Relay...", style = DtnType.Meta)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(onlinePeers.keys.toList()) { peerId ->
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

@Composable
fun ConsoleLogView(lifecycleLog: MessageLifecycleLog) {
    val logs by lifecycleLog.console.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("System Diagnostics & Journal", style = DtnType.PageTitle)

        Surface(
            shape = DtnShapes.Card,
            color = DtnColors.Terminal,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(logs) { log ->
                    Text(
                        text = log,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF38BDF8))
                    )
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
