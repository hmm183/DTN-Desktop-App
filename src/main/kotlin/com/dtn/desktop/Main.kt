package com.dtn.desktop

import androidx.compose.animation.*
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import com.dtn.mesh.crypto.DtnIdentity
import com.dtn.mesh.crypto.KeyVault
import com.dtn.mesh.database.ChatMessage
import com.dtn.mesh.database.DesktopDatabase
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.routing.StrategySelector.StrategyType
import com.dtn.mesh.service.DesktopOrchestrator
import com.dtn.mesh.service.MessageLifecycleLog
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class DesktopNavTab { CHATS, QUEUE, TOPOLOGY, CONSOLE }

fun main() = application {
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
        size = DpSize(1200.dp, 800.dp)
    )

    Window(
        onCloseRequest = {
            orchestrator.stop()
            exitApplication()
        },
        title = "DTN Mesh Relay — Desktop Command Station (${identity.nodeId})",
        state = windowState
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFF3B82F6),
                secondary = Color(0xFF10B981),
                background = Color(0xFF0F172A),
                surface = Color(0xFF1E293B),
                surfaceVariant = Color(0xFF334155),
                onBackground = Color(0xFFF8FAFC),
                onSurface = Color(0xFFF1F5F9),
            )
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                DesktopAppScreen(orchestrator, database, lifecycleLog)
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
    var selectedConvKey by remember { mutableStateOf("^all") } // Default to Broadcast
    val chatMessages by database.chatMessages.collectAsState()
    val contacts by database.contacts.collectAsState()
    val bufferedMessages by database.bufferedMessages.collectAsState()
    val onlinePeers by orchestrator.transportManager.onlinePeers.collectAsState()
    val activeStrategy by orchestrator.activeStrategy.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // ── TOP BAR: Node Status & Radios ─────────────────────────────────
        TopStatusBar(
            orchestrator = orchestrator,
            activeStrategy = activeStrategy,
            onStrategyChanged = { orchestrator.setStrategy(it) },
            onBroadcastAnnounce = { orchestrator.broadcastAnnounce() }
        )

        Divider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 1.dp)

        // ── MAIN CONTENT AREA ─────────────────────────────────────────────
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Left Rail Navigation & Chats
            LeftSidebar(
                activeTab = activeTab,
                onTabSelected = { activeTab = it },
                selectedConvKey = selectedConvKey,
                onConvSelected = { selectedConvKey = it },
                contacts = contacts,
                onlinePeers = onlinePeers,
                bufferedCount = bufferedMessages.size,
                onAddContact = { id, name ->
                    database.upsertContact(
                        com.dtn.mesh.database.Contact(nodeId = id, announceName = name, isAdded = true)
                    )
                }
            )

            Divider(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxHeight().width(1.dp)
            )

            // Center Content
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (activeTab) {
                    DesktopNavTab.CHATS -> {
                        Row(modifier = Modifier.fillMaxSize()) {
                            // Active Chat
                            ChatView(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                convKey = selectedConvKey,
                                messages = chatMessages.filter { it.convKey == selectedConvKey },
                                onSendMessage = { text ->
                                    val dest = if (selectedConvKey == "^all") NodeId.BROADCAST else NodeId(selectedConvKey)
                                    orchestrator.sendTextMessage(dest, text)
                                }
                            )

                            Divider(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxHeight().width(1.dp)
                            )

                            // Right Details Panel
                            NodeInspectorPanel(
                                modifier = Modifier.width(320.dp).fillMaxHeight(),
                                convKey = selectedConvKey,
                                contacts = contacts,
                                onlinePeers = onlinePeers,
                                orchestrator = orchestrator
                            )
                        }
                    }
                    DesktopNavTab.QUEUE -> {
                        QueueInspectorView(bufferedMessages)
                    }
                    DesktopNavTab.TOPOLOGY -> {
                        TopologyView(orchestrator, onlinePeers, contacts)
                    }
                    DesktopNavTab.CONSOLE -> {
                        ConsoleView(lifecycleLog)
                    }
                }
            }
        }
    }
}

@Composable
fun TopStatusBar(
    orchestrator: DesktopOrchestrator,
    activeStrategy: StrategyType,
    onStrategyChanged: (StrategyType) -> Unit,
    onBroadcastAnnounce: () -> Unit,
) {
    val usbConnected by orchestrator.transportManager.usbSerial.isConnected.collectAsState()
    val usbPort by orchestrator.transportManager.usbSerial.connectedPortName.collectAsState()
    val tcpHubConnected by orchestrator.transportManager.tcpHub.isConnected.collectAsState()
    val bleScanning by orchestrator.transportManager.ble.isScanning.collectAsState()
    val wifiDirectListening by orchestrator.transportManager.wifiDirect.isListening.collectAsState()
    val internetConnected by orchestrator.transportManager.internet.isConnected.collectAsState()

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().height(64.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Node Identity
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Lan, contentDescription = null, tint = Color.White)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Station: ${orchestrator.identity.nodeId}",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                    Text(
                        text = System.getenv("COMPUTERNAME") ?: "Windows Command Station",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
            }

            // Center: 5 Radio Status Indicators
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioPill("USB LoRa", if (usbConnected) "Linked ($usbPort)" else "Offline", usbConnected, Icons.Default.Usb)
                RadioPill("Hub Wi-Fi", if (tcpHubConnected) "SoftAP :9740" else "Offline", tcpHubConnected, Icons.Default.Wifi)
                RadioPill("BLE Radio", if (bleScanning) "Active" else "Idle", bleScanning, Icons.Default.Bluetooth)
                RadioPill("Wi-Fi Direct", if (wifiDirectListening) "Port :9734" else "Off", wifiDirectListening, Icons.Default.WifiTethering)
                RadioPill("Cloud Relay", if (internetConnected) "HiveMQ/Worker" else "Offline", internetConnected, Icons.Default.Cloud)
            }

            // Right: Strategy & Announce
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StrategyDropdown(activeStrategy, onStrategyChanged)

                Button(
                    onClick = onBroadcastAnnounce,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.BroadcastOnHome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Announce", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun RadioPill(label: String, status: String, active: Boolean, icon: ImageVector) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (active) Color(0xFF064E3B) else Color(0xFF1E293B),
        border = BorderStroke(1.dp, if (active) Color(0xFF10B981) else Color(0xFF475569))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) Color(0xFF34D399) else Color(0xFF94A3B8),
                modifier = Modifier.size(14.dp)
            )
            Column {
                Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(status, fontSize = 9.sp, color = if (active) Color(0xFFA7F3D0) else Color(0xFF64748B))
            }
        }
    }
}

@Composable
fun StrategyDropdown(current: StrategyType, onSelected: (StrategyType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            border = BorderStroke(1.dp, Color(0xFF3B82F6)),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("Router: ${current.name}", fontSize = 12.sp, color = Color(0xFF93C5FD))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFF93C5FD))
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            StrategyType.entries.forEach { st ->
                DropdownMenuItem(
                    text = { Text(st.name, fontWeight = if (st == current) FontWeight.Bold else FontWeight.Normal) },
                    onClick = {
                        onSelected(st)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun LeftSidebar(
    activeTab: DesktopNavTab,
    onTabSelected: (DesktopNavTab) -> Unit,
    selectedConvKey: String,
    onConvSelected: (String) -> Unit,
    contacts: List<com.dtn.mesh.database.Contact>,
    onlinePeers: Map<String, com.dtn.mesh.transport.PeerEndpoint>,
    bufferedCount: Int,
    onAddContact: (String, String) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.width(280.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
        // Tab buttons
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            NavTabButton(Icons.Default.Chat, "Chats", activeTab == DesktopNavTab.CHATS) { onTabSelected(DesktopNavTab.CHATS) }
            NavTabButton(Icons.Default.HourglassEmpty, "Queue ($bufferedCount)", activeTab == DesktopNavTab.QUEUE) { onTabSelected(DesktopNavTab.QUEUE) }
            NavTabButton(Icons.Default.AccountTree, "Map", activeTab == DesktopNavTab.TOPOLOGY) { onTabSelected(DesktopNavTab.TOPOLOGY) }
            NavTabButton(Icons.Default.Terminal, "Console", activeTab == DesktopNavTab.CONSOLE) { onTabSelected(DesktopNavTab.CONSOLE) }
        }

        Divider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 1.dp)

        if (activeTab == DesktopNavTab.CHATS) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Conversations", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                IconButton(onClick = { showAddDialog = true }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.PersonAdd, contentDescription = "Add Contact", tint = MaterialTheme.colorScheme.primary)
                }
            }

            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Pinned Broadcast Chat
                item {
                    ConversationRow(
                        title = "📢 Public Broadcast",
                        subtitle = "Mesh-wide unencrypted emergency",
                        isSelected = selectedConvKey == "^all",
                        isOnline = true,
                        badge = "Mesh",
                        onClick = { onConvSelected("^all") }
                    )
                }

                items(contacts) { c ->
                    val isOnline = onlinePeers.containsKey(c.nodeId)
                    val link = onlinePeers[c.nodeId]?.linkType ?: "Offline"
                    ConversationRow(
                        title = c.announceName ?: c.nodeId,
                        subtitle = "${c.nodeId} · $link",
                        isSelected = selectedConvKey == c.nodeId,
                        isOnline = isOnline,
                        badge = if (c.keyVerified) "E2EE" else "Unverified",
                        onClick = { onConvSelected(c.nodeId) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddContactDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { id, name ->
                onAddContact(id, name)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun NavTabButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
        modifier = Modifier.clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = if (active) Color.White else Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
            Text(label, fontSize = 10.sp, color = if (active) Color.White else Color(0xFF94A3B8))
        }
    }
}

@Composable
fun ConversationRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    isOnline: Boolean,
    badge: String,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) Color(0xFF1E3A8A) else Color.Transparent,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(10.dp).clip(CircleShape)
                    .background(if (isOnline) Color(0xFF10B981) else Color(0xFF64748B))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White)
                    Text(badge, fontSize = 9.sp, color = Color(0xFF60A5FA))
                }
                Text(subtitle, fontSize = 11.sp, color = Color(0xFF94A3B8), maxLines = 1)
            }
        }
    }
}

@Composable
fun ChatView(
    modifier: Modifier = Modifier,
    convKey: String,
    messages: List<ChatMessage>,
    onSendMessage: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = modifier.background(Color(0xFF0B132B))) {
        // Chat Header
        Surface(color = Color(0xFF1C2541), modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (convKey == "^all") Icons.Default.Campaign else Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color(0xFF60A5FA),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (convKey == "^all") "Public Broadcast Channel" else "Direct Message: $convKey",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
            }
        }

        // Messages List
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                val isOut = msg.direction == "OUT"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isOut) Color(0xFF2563EB) else Color(0xFF1E293B),
                        modifier = Modifier.widthIn(max = 500.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(msg.text, color = Color.White, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.createdAtMs))
                                Text(timeStr, fontSize = 10.sp, color = Color(0xFF93C5FD))
                                if (isOut) {
                                    val statusIcon = when (msg.status) {
                                        "DELIVERED" -> "✓✓"
                                        "SENT" -> "✓"
                                        else -> "🕓"
                                    }
                                    Text(statusIcon, fontSize = 10.sp, color = Color(0xFFA7F3D0))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Message Input
        Surface(color = Color(0xFF1C2541), modifier = Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Type message... (Enter to send)") },
                    modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
                            if (inputText.isNotBlank()) {
                                onSendMessage(inputText.trim())
                                inputText = ""
                            }
                            true
                        } else false
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF475569)
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onSendMessage(inputText.trim())
                            inputText = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
fun NodeInspectorPanel(
    modifier: Modifier = Modifier,
    convKey: String,
    contacts: List<com.dtn.mesh.database.Contact>,
    onlinePeers: Map<String, com.dtn.mesh.transport.PeerEndpoint>,
    orchestrator: DesktopOrchestrator
) {
    val contact = contacts.firstOrNull { it.nodeId == convKey }
    val isOnline = onlinePeers.containsKey(convKey)
    val link = onlinePeers[convKey]?.linkType ?: "Offline"

    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Node Inspector", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)

        if (convKey == "^all") {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Broadcast Domain", fontWeight = FontWeight.Bold, color = Color(0xFF60A5FA))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Address: 0xFFFFFFFF (^all)", fontSize = 12.sp, color = Color.White)
                    Text("Encryption: None (Cleartext)", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    Text("Routing: Epidemic Controlled Flood", fontSize = 12.sp, color = Color(0xFF94A3B8))
                }
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(contact?.announceName ?: "Node $convKey", fontWeight = FontWeight.Bold, color = Color.White)
                    Text("ID: $convKey", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    Text("Link: $link", fontSize = 12.sp, color = if (isOnline) Color(0xFF34D399) else Color(0xFFF87171))
                    Text("E2EE: ${if (contact?.xPub != null) "X25519 Verified" else "Keys Missing"}", fontSize = 12.sp, color = Color(0xFF60A5FA))
                    Text("PRoPHET P: ${String.format("%.3f", contact?.deliveryProbability ?: 0.0)}", fontSize = 12.sp, color = Color.White)
                    Text("Historical Deliveries: ${contact?.deliverySuccessCount ?: 0}", fontSize = 12.sp, color = Color(0xFF94A3B8))
                }
            }
        }
    }
}

@Composable
fun QueueInspectorView(bufferedMessages: List<com.dtn.mesh.model.DtnMessage>) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Store-Carry-Forward Buffer (${bufferedMessages.size} in transit)", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(bufferedMessages) { msg ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Msg: ${msg.id.take(8)}... -> Dest: ${msg.destinationNodeId}", fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Origin: ${msg.originNodeId} · Hops: ${msg.hopCount} · Forwards: ${msg.forwardCount}", fontSize = 12.sp, color = Color(0xFF94A3B8))
                        }
                        Text("TTL: ${msg.remainingTtlMs() / 60000}m left", fontSize = 12.sp, color = Color(0xFFFBBF24))
                    }
                }
            }
        }
    }
}

@Composable
fun TopologyView(
    orchestrator: DesktopOrchestrator,
    onlinePeers: Map<String, com.dtn.mesh.transport.PeerEndpoint>,
    contacts: List<com.dtn.mesh.database.Contact>
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Network Topology & Link Map", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
        Spacer(modifier = Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Local Node: ${orchestrator.identity.nodeId} (DESKTOP STATION)", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                Divider(color = Color(0xFF334155))
                Text("Direct Online Neighbors (${onlinePeers.size}):", fontWeight = FontWeight.SemiBold, color = Color.White)
                onlinePeers.forEach { (id, ep) ->
                    Text("• $id via ${ep.linkType}", color = Color(0xFF34D399), fontSize = 13.sp)
                }
                if (onlinePeers.isEmpty()) {
                    Text("No direct radio neighbors currently detected. Listening on BLE, Wi-Fi Direct, LoRa Hub & Relay...", color = Color(0xFF94A3B8), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun ConsoleView(lifecycleLog: MessageLifecycleLog) {
    val logs by lifecycleLog.console.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Real-Time Telemetry & Console", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
        Spacer(modifier = Modifier.height(12.dp))

        Surface(color = Color(0xFF0F172A), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(logs) { logLine ->
                    Text(logLine, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF38BDF8))
                }
            }
        }
    }
}

@Composable
fun AddContactDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var idInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Mesh Contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = idInput, onValueChange = { idInput = it }, label = { Text("Node ID (!aabbccdd)") })
                OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, label = { Text("Display Name") })
            }
        },
        confirmButton = {
            Button(onClick = {
                if (idInput.isNotBlank()) onAdd(idInput.trim(), nameInput.trim())
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
