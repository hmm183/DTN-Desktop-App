package com.dtn.mesh.transport

import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.receiver.DtnWireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.dtn.mesh.service.DiagLevel
import com.dtn.mesh.service.MessageLifecycleLog
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Base64
import java.util.UUID

/**
 * Windows BLE Transport Adapter.
 *
 * Utilizes exact DTN BLE Service and Characteristic UUIDs matching the Android implementation:
 * - Service UUID: d791b000-2c8f-4f2a-9b1e-000000000001
 * - Bundle Characteristic: d791b001-2c8f-4f2a-9b1e-000000000001
 * - Link Characteristic: d791b002-2c8f-4f2a-9b1e-000000000001
 *
 * Communicates with the native .NET 9 WinRT BLE bridge worker (dtn-ble-worker.exe).
 */
class WindowsBleTransportAdapter(
    private val localNodeId: NodeId,
    private val onMessageReceived: (DtnMessage, String) -> Unit,
    private val onPeerStatusChanged: (String, Boolean, String) -> Unit,
    val lifecycleLog: MessageLifecycleLog? = null,
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("d791b000-2c8f-4f2a-9b1e-000000000001")
        val BUNDLE_CHAR_UUID: UUID = UUID.fromString("d791b001-2c8f-4f2a-9b1e-000000000001")
        val LINK_CHAR_UUID: UUID = UUID.fromString("d791b002-2c8f-4f2a-9b1e-000000000001")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isBleAvailable = MutableStateFlow(true)
    val isBleAvailable: StateFlow<Boolean> = _isBleAvailable.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<Map<String, String>>(emptyMap()) // peerId -> MAC
    val discoveredPeers: StateFlow<Map<String, String>> = _discoveredPeers.asStateFlow()

    private var workerProcess: Process? = null
    private var workerWriter: BufferedWriter? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        val workerExe = locateWorkerExe()
        if (workerExe == null || !workerExe.exists()) {
            println("[WindowsBle] Warning: dtn-ble-worker.exe not found. BLE hardware integration will remain passive.")
            _isBleAvailable.value = false
            return
        }

        scope.launch {
            try {
                println("[WindowsBle] Starting BLE bridge worker: ${workerExe.absolutePath}")
                val pb = ProcessBuilder(
                    workerExe.absolutePath,
                    "--node-id", localNodeId.value,
                    "--node-num", localNodeId.toNodeNum32().toString()
                )
                pb.redirectErrorStream(true)
                val process = pb.start()
                workerProcess = process

                val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
                workerWriter = writer

                _isScanning.value = true
                _isBleAvailable.value = true

                val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
                var line: String? = null
                while (scope.isActive && reader.readLine().also { line = it } != null) {
                    val l = line?.trim() ?: continue
                    if (l.isEmpty()) continue
                    handleWorkerEvent(l)
                }
            } catch (e: Exception) {
                println("[WindowsBle] Worker error: ${e.message}")
                _isBleAvailable.value = false
            }
        }
    }

    fun stop() {
        _isScanning.value = false
        try {
            workerWriter?.write("{\"cmd\":\"stop\"}\n")
            workerWriter?.flush()
        } catch (_: Exception) {}
        workerProcess?.destroy()
        workerProcess = null
        workerWriter = null
    }

    private fun handleWorkerEvent(line: String) {
        try {
            val root = json.parseToJsonElement(line).jsonObject
            val event = root["event"]?.jsonPrimitive?.content ?: return

            when (event) {
                "log" -> {
                    val src = root["source"]?.jsonPrimitive?.content ?: "BLE"
                    val lvl = root["level"]?.jsonPrimitive?.content ?: "INFO"
                    val txt = root["text"]?.jsonPrimitive?.content ?: ""
                    val diagLevel = when (lvl.uppercase()) {
                        "DEBUG" -> DiagLevel.DEBUG
                        "WARN" -> DiagLevel.WARN
                        "ERROR" -> DiagLevel.ERROR
                        else -> DiagLevel.INFO
                    }
                    lifecycleLog?.logDiag(diagLevel, src, txt)
                }
                "discovered" -> {
                    val nodeId = root["nodeId"]?.jsonPrimitive?.content ?: return
                    val mac = root["mac"]?.jsonPrimitive?.content ?: ""
                    val rssi = root["rssi"]?.jsonPrimitive?.content?.toIntOrNull() ?: -65

                    val current = _discoveredPeers.value.toMutableMap()
                    current[nodeId] = mac
                    _discoveredPeers.value = current
                    onPeerStatusChanged(nodeId, true, "BLE ($rssi dBm)")
                    lifecycleLog?.i("BLE", "Discovered peer $nodeId (MAC $mac, RSSI $rssi dBm)")
                }
                "bundle" -> {
                    val wireB64 = root["wireBase64"]?.jsonPrimitive?.content ?: return
                    val rssi = root["rssi"]?.jsonPrimitive?.content?.toIntOrNull() ?: -55
                    val wireBytes = Base64.getDecoder().decode(wireB64)
                    val bundle = DtnWireCodec.decode(wireBytes, rssi = rssi, snr = 10f)
                    if (bundle != null && bundle.originNodeId != localNodeId) {
                        lifecycleLog?.i("BLE", "Inbound bundle received: ${bundle.id.take(8)} from ${bundle.originNodeId.value} (${wireBytes.size} B, RSSI $rssi dBm)")
                        onMessageReceived(bundle, "BLE")
                    } else if (bundle != null) {
                        lifecycleLog?.d("BLE", "Ignored bundle ${bundle.id.take(8)} originated from self")
                    }
                }
                "status" -> {
                    val available = root["available"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                    _isBleAvailable.value = available
                    val msg = root["message"]?.jsonPrimitive?.content ?: ""
                    lifecycleLog?.i("BLE", "Worker status: available=$available ($msg)")
                }
                "error" -> {
                    val err = root["error"]?.jsonPrimitive?.content ?: ""
                    lifecycleLog?.w("BLE", "Worker notice: $err")
                }
                "linkUp" -> {
                    val mac = root["mac"]?.jsonPrimitive?.content ?: ""
                    lifecycleLog?.i("BLE", "GATT link confirmed with $mac: subscribed to indications & BleHello ACKed")
                }
                "sent" -> {
                    val nodeId = root["nodeId"]?.jsonPrimitive?.content ?: ""
                    val success = root["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    val error = root["error"]?.jsonPrimitive?.content ?: ""
                    if (success) {
                        lifecycleLog?.i("BLE", "Bundle successfully delivered to $nodeId via GATT write")
                    } else {
                        lifecycleLog?.w("BLE", "Bundle delivery failed to $nodeId: $error")
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun sendBundle(bundle: DtnMessage, targetPeerId: String): Boolean {
        val writer = workerWriter ?: run {
            lifecycleLog?.w("BLE", "Cannot send bundle: BLE worker process not active")
            return false
        }
        val mac = _discoveredPeers.value[targetPeerId] ?: ""
        return try {
            val wire = DtnWireCodec.encode(bundle)
            val b64 = Base64.getEncoder().encodeToString(wire)
            val cmd = "{\"cmd\":\"send\",\"nodeId\":\"$targetPeerId\",\"mac\":\"$mac\",\"wireBase64\":\"$b64\"}\n"
            lifecycleLog?.d("BLE", "Queuing bundle ${bundle.id.take(8)} to $targetPeerId (${wire.size} B via ${if (mac.isNotBlank()) mac else "GATT_CENTRAL"})")
            synchronized(writer) {
                writer.write(cmd)
                writer.flush()
            }
            true
        } catch (e: Exception) {
            lifecycleLog?.e("BLE", "sendBundle failed to $targetPeerId: ${e.message}")
            false
        }
    }

    private fun locateWorkerExe(): File? {
        val baseDir = try {
            File(WindowsBleTransportAdapter::class.java.protectionDomain.codeSource.location.toURI()).parentFile
        } catch (_: Exception) { null }

        val candidates = listOfNotNull(
            File("tools/ble-worker/bin/Release/net9.0-windows10.0.19041.0/win-x64/publish/dtn-ble-worker.exe"),
            File("tools/ble-worker/bin/Release/net9.0-windows10.0.19041.0/dtn-ble-worker.exe"),
            File(System.getProperty("user.dir"), "tools/ble-worker/bin/Release/net9.0-windows10.0.19041.0/win-x64/publish/dtn-ble-worker.exe"),
            File(System.getProperty("user.dir"), "dtn-ble-worker.exe"),
            File(".", "dtn-ble-worker.exe"),
            File("dtn-ble-worker.exe"),
            baseDir?.let { File(it, "dtn-ble-worker.exe") },
            baseDir?.parentFile?.let { File(it, "dtn-ble-worker.exe") },
            baseDir?.parentFile?.let { File(it, "app/dtn-ble-worker.exe") },
            File(System.getProperty("compose.application.resources.dir", "."), "dtn-ble-worker.exe")
        )
        return candidates.firstOrNull { it.exists() }
    }
}
