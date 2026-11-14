package com.dtn.desktop.simulation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.desktop.theme.DtnColors
import com.dtn.desktop.theme.DtnShapes
import com.dtn.desktop.theme.DtnType
import kotlinx.coroutines.launch

@Composable
fun DiMaxPropSimulationView(
    simService: DiMaxPropSimulationService = remember { DiMaxPropSimulationService() }
) {
    val scenarios = remember { simService.listScenarios() }
    var selectedScenario by remember { mutableStateOf(scenarios.first()) }
    var lambdaValue by remember { mutableStateOf(0.5f) }
    var alphaValue by remember { mutableStateOf(1.0f) }
    var bufferMb by remember { mutableStateOf(5) }

    var isRunning by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var simReport by remember { mutableStateOf<SimReport?>(null) }
    var showRawReport by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ── HEADER ────────────────────────────────────────────────────────────
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = DtnShapes.Pill,
                        color = DtnColors.AlgoDiMaxProp.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, DtnColors.AlgoDiMaxProp.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "DIMAXPROP RESEARCH & SIMULATION",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DtnColors.AlgoDiMaxProp)
                        )
                    }

                    Surface(
                        shape = DtnShapes.Pill,
                        color = if (simService.isAvailable()) DtnColors.GreenSoft else DtnColors.OrangeSoft
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(if (simService.isAvailable()) DtnColors.Green else DtnColors.Orange))
                            Text(
                                text = if (simService.isAvailable()) "ONE Engine Ready" else "Simulation Engine Not Found",
                                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (simService.isAvailable()) DtnColors.GreenText else DtnColors.OrangeText)
                            )
                        }
                    }
                }

                Text("DiMaxProp Protocol Simulator & Benchmark Studio", style = DtnType.SectionHeader.copy(fontSize = 20.sp))
                Text(
                    "Simulates Distance-Integrated MaxProp routing: cost w(u,v) = (1 − f(u,v)) + λ·d_norm(u,v). Evaluate delivery ratio, hop counts, and latency across Helsinki or custom mobility models.",
                    style = DtnType.Meta.copy(fontSize = 13.sp)
                )
            }
        }

        // ── SCENARIO SELECTOR ──────────────────────────────────────────────────
        item {
            Card(
                shape = DtnShapes.Card,
                colors = CardDefaults.cardColors(containerColor = DtnColors.Card),
                border = BorderStroke(1.dp, DtnColors.CardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("1. CHOOSE SIMULATION SCENARIO", style = DtnType.SectionHeader)

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        scenarios.forEach { scenario ->
                            val isSelected = selectedScenario.id == scenario.id
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) DtnColors.PrimarySoft else Color.White,
                                border = BorderStroke(1.dp, if (isSelected) DtnColors.Primary else DtnColors.CardBorder),
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                    selectedScenario = scenario
                                    lambdaValue = scenario.defaultLambda.toFloat()
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            selectedScenario = scenario
                                            lambdaValue = scenario.defaultLambda.toFloat()
                                        },
                                        colors = RadioButtonDefaults.colors(selectedColor = DtnColors.Primary)
                                    )

                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(scenario.title, style = DtnType.RowTitle.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold))
                                            Surface(
                                                shape = DtnShapes.Pill,
                                                color = DtnColors.algorithm(scenario.router).copy(alpha = 0.12f)
                                            ) {
                                                Text(
                                                    scenario.router,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                    style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DtnColors.algorithm(scenario.router))
                                                )
                                            }
                                        }
                                        Text(scenario.description, style = DtnType.Meta)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── PARAMETERS & CONTROLS ──────────────────────────────────────────────
        item {
            Card(
                shape = DtnShapes.Card,
                colors = CardDefaults.cardColors(containerColor = DtnColors.Card),
                border = BorderStroke(1.dp, DtnColors.CardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("2. CONFIGURE PARAMETERS & LAUNCH", style = DtnType.SectionHeader)

                    // Lambda Slider
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Distance Weight (λ): ${String.format(java.util.Locale.US, "%.2f", lambdaValue)}", style = DtnType.RowTitle)
                            Text(
                                text = when {
                                    lambdaValue == 0f -> "λ = 0.0: Standard MaxProp (Pure Meeting Probability)"
                                    lambdaValue < 0.4f -> "Light Distance Influence"
                                    lambdaValue <= 0.6f -> "Balanced Hybrid (Recommended)"
                                    else -> "Strong Distance Prioritization"
                                },
                                style = TextStyle(fontSize = 12.sp, color = DtnColors.AlgoDiMaxProp, fontWeight = FontWeight.SemiBold)
                            )
                        }

                        Slider(
                            value = lambdaValue,
                            onValueChange = { lambdaValue = it },
                            valueRange = 0.0f..1.0f,
                            steps = 19,
                            colors = SliderDefaults.colors(
                                thumbColor = DtnColors.AlgoDiMaxProp,
                                activeTrackColor = DtnColors.AlgoDiMaxProp
                            )
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0.0f to "0.0 (MaxProp)", 0.25f to "0.25", 0.5f to "0.5 (Default)", 0.75f to "0.75", 1.0f to "1.0 (Distance)").forEach { (v, label) ->
                                OutlinedButton(
                                    onClick = { lambdaValue = v },
                                    shape = DtnShapes.Pill,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(28.dp),
                                    colors = if (lambdaValue == v) ButtonDefaults.outlinedButtonColors(containerColor = DtnColors.AlgoDiMaxProp.copy(alpha = 0.15f)) else ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text(label, style = TextStyle(fontSize = 10.sp))
                                }
                            }
                        }
                    }

                    HorizontalDivider(thickness = 1.dp, color = DtnColors.CardBorder)

                    // Buffer Size Chips
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Host Buffer Size:", style = DtnType.RowTitle)
                        listOf(2 to "2 MB", 5 to "5 MB", 10 to "10 MB", 20 to "20 MB").forEach { (size, label) ->
                            FilterChip(
                                selected = bufferMb == size,
                                onClick = { bufferMb = size },
                                label = { Text(label, style = TextStyle(fontSize = 11.sp)) },
                                shape = DtnShapes.Pill
                            )
                        }
                    }

                    HorizontalDivider(thickness = 1.dp, color = DtnColors.CardBorder)

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isRunning = true
                                    statusMessage = "Starting simulation..."
                                    try {
                                        val report = simService.runBenchmark(
                                            scenarioFilename = selectedScenario.filename,
                                            lambda = lambdaValue.toDouble(),
                                            alpha = alphaValue.toDouble(),
                                            bufferMb = bufferMb,
                                            onProgress = { statusMessage = it }
                                        )
                                        simReport = report
                                        statusMessage = "Completed in ${report.executionTimeMs}ms"
                                    } catch (e: Exception) {
                                        statusMessage = "Error: ${e.message}"
                                    } finally {
                                        isRunning = false
                                    }
                                }
                            },
                            enabled = !isRunning && simService.isAvailable(),
                            colors = ButtonDefaults.buttonColors(containerColor = DtnColors.AlgoDiMaxProp),
                            shape = DtnShapes.Button,
                            modifier = Modifier.height(44.dp)
                        ) {
                            if (isRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Simulating...", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold))
                            } else {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Run In-App Benchmark", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold))
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                val proc = simService.launchInteractiveVisualizer(selectedScenario.filename)
                                statusMessage = if (proc != null) "Launched ONE Simulator GUI window" else "Failed to launch GUI"
                            },
                            enabled = simService.isAvailable(),
                            shape = DtnShapes.Button,
                            modifier = Modifier.height(44.dp)
                        ) {
                            Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Launch ONE Interactive Visualizer (GUI)", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                        }

                        if (statusMessage != null) {
                            Text(
                                text = statusMessage!!,
                                style = TextStyle(fontSize = 12.sp, color = DtnColors.TextSubtitle, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                            )
                        }
                    }
                }
            }
        }

        // ── RESULTS DASHBOARD ──────────────────────────────────────────────────
        if (simReport != null) {
            val report = simReport!!
            item {
                Card(
                    shape = DtnShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = DtnColors.Card),
                    border = BorderStroke(1.dp, DtnColors.CardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("3. SIMULATION RESULTS: ${report.scenarioName}", style = DtnType.SectionHeader)
                            Surface(shape = DtnShapes.Pill, color = DtnColors.GreenSoft) {
                                Text(
                                    "Runtime: ${report.executionTimeMs} ms",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DtnColors.GreenText)
                                )
                            }
                        }

                        // 4 Big KPI Cards
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricKpiCard(
                                title = "Delivery Ratio",
                                value = "${String.format(java.util.Locale.US, "%.1f", report.deliveryProbability * 100)}%",
                                subtitle = "${report.delivered}/${report.created} delivered",
                                color = if (report.delivered > 0) DtnColors.Green else DtnColors.Orange,
                                modifier = Modifier.weight(1f)
                            )
                            MetricKpiCard(
                                title = "Average Latency",
                                value = if (report.avgLatency.isNaN()) "N/A" else "${String.format(java.util.Locale.US, "%.2f", report.avgLatency)}s",
                                subtitle = "Simulated transit time",
                                color = DtnColors.Primary,
                                modifier = Modifier.weight(1f)
                            )
                            MetricKpiCard(
                                title = "Average Hops",
                                value = if (report.avgHops.isNaN()) "N/A" else String.format(java.util.Locale.US, "%.1f", report.avgHops),
                                subtitle = "Intermediate carriers",
                                color = DtnColors.AlgoDiMaxProp,
                                modifier = Modifier.weight(1f)
                            )
                            MetricKpiCard(
                                title = "Overhead Ratio",
                                value = if (report.overheadRatio.isNaN()) "N/A" else "${String.format(java.util.Locale.US, "%.2f", report.overheadRatio)}x",
                                subtitle = "Relays per delivery",
                                color = DtnColors.Orange,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Hop Path Trace (if available)
                        if (report.finalPath.isNotBlank() || report.hopSteps.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White,
                                border = BorderStroke(1.dp, DtnColors.CardBorder),
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("DISCOVERED ROUTING PATH", style = DtnType.RowTitle)
                                    if (report.finalPath.isNotBlank()) {
                                        Surface(
                                            shape = DtnShapes.Pill,
                                            color = DtnColors.GreenSoft,
                                            border = BorderStroke(1.dp, DtnColors.Green.copy(alpha = 0.3f))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(Icons.Filled.Route, contentDescription = null, tint = DtnColors.GreenText, modifier = Modifier.size(16.dp))
                                                Text(
                                                    text = "Final End-to-End Route: ${report.finalPath}",
                                                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DtnColors.GreenText)
                                                )
                                            }
                                        }
                                    }

                                    if (report.hopSteps.isNotEmpty()) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            report.hopSteps.forEach { step ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Surface(shape = CircleShape, color = DtnColors.Primary, modifier = Modifier.size(22.dp)) {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Text("${step.hopNumber}", style = TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold))
                                                        }
                                                    }
                                                    Text("T = ${String.format(java.util.Locale.US, "%.1f", step.timestamp)}s", style = DtnType.Mono)
                                                    Text("Node ${step.fromNode}", fontWeight = FontWeight.Bold)
                                                    Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = DtnColors.TextHint, modifier = Modifier.size(14.dp))
                                                    Text("Node ${step.toNode}", fontWeight = FontWeight.Bold, color = DtnColors.Primary)
                                                    Spacer(Modifier.weight(1f))
                                                    Surface(shape = DtnShapes.Pill, color = DtnColors.GreySoft) {
                                                        Text(step.action, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = TextStyle(fontSize = 10.sp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Toggle Raw Log Output
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showRawReport = !showRawReport }) {
                                Text(if (showRawReport) "Hide Raw Output ▲" else "View Raw Engine Output ▼", style = TextStyle(fontSize = 12.sp))
                            }
                        }

                        AnimatedVisibility(visible = showRawReport) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF1E293B),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = report.rawReportText,
                                    style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFE2E8F0)),
                                    modifier = Modifier.padding(14.dp)
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
fun MetricKpiCard(title: String, value: String, subtitle: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = BorderStroke(1.dp, DtnColors.CardBorder),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = DtnType.Meta)
            Text(value, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color))
            Text(subtitle, style = TextStyle(fontSize = 11.sp, color = DtnColors.TextSubtitle))
        }
    }
}
