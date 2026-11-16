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

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

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
        scope.launch {
            try {
                var workerExe = locateWorkerExe()
                if (workerExe == null || !workerExe.exists()) {
                    lifecycleLog?.i("BLE", "Native BLE worker not found locally. Attempting automatic extraction / download...")
                    workerExe = ensureWorkerExe()
                }

                if (workerExe == null || !workerExe.exists()) {
                    println("[WindowsBle] Warning: dtn-ble-worker.exe could not be resolved or downloaded. BLE will remain passive.")
                    lifecycleLog?.w("BLE", "dtn-ble-worker.exe could not be found or installed. BLE hardware integration will remain passive.")
                    _isBleAvailable.value = false
                    return@launch
                }

                println("[WindowsBle] Starting BLE bridge worker: ${workerExe.absolutePath}")
                lifecycleLog?.i("BLE", "Starting BLE bridge worker (${workerExe.name})")
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
                lifecycleLog?.e("BLE", "Worker error: ${e.message}")
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

        val userAppDataBin = File(System.getProperty("user.home"), ".dtnmesh/bin")

        val candidates = listOfNotNull(
            File("tools/ble-worker/bin/Release/net9.0-windows10.0.19041.0/win-x64/publish/dtn-ble-worker.exe"),
            File("tools/ble-worker/bin/Release/net9.0-windows10.0.19041.0/dtn-ble-worker.exe"),
            File(System.getProperty("user.dir"), "tools/ble-worker/bin/Release/net9.0-windows10.0.19041.0/win-x64/publish/dtn-ble-worker.exe"),
            File(userAppDataBin, "dtn-ble-worker.exe"),
            File(System.getProperty("user.dir"), "dtn-ble-worker.exe"),
            File(".", "dtn-ble-worker.exe"),
            File("dtn-ble-worker.exe"),
            baseDir?.let { File(it, "dtn-ble-worker.exe") },
            baseDir?.parentFile?.let { File(it, "dtn-ble-worker.exe") },
            baseDir?.parentFile?.let { File(it, "app/dtn-ble-worker.exe") },
            File(System.getProperty("compose.application.resources.dir", "."), "dtn-ble-worker.exe")
        )
        return candidates.firstOrNull { it.exists() && it.length() > 50_000L }
    }

    private fun ensureWorkerExe(): File? {
        val userAppDataBin = File(System.getProperty("user.home"), ".dtnmesh/bin").apply { mkdirs() }
        val targetWorker = File(userAppDataBin, "dtn-ble-worker.exe")
        if (targetWorker.exists() && targetWorker.length() > 50_000L) {
            return targetWorker
        }

        // 1. Try extracting from classpath / JAR resources
        try {
            val resStream = javaClass.getResourceAsStream("/dtn-ble-worker.exe")
                ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("dtn-ble-worker.exe")
            if (resStream != null) {
                lifecycleLog?.i("BLE", "Extracting bundled native BLE bridge worker...")
                resStream.use { input ->
                    targetWorker.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (targetWorker.exists() && targetWorker.length() > 50_000L) {
                    return targetWorker
                }
            }
        } catch (_: Exception) {}

        // 2. Try compiling locally if dotnet & BleWorker.csproj exist
        try {
            val csproj = File("tools/ble-worker/BleWorker.csproj")
            if (csproj.exists()) {
                val userDotnet = File(System.getProperty("user.home"), ".dotnet/dotnet.exe")
                val dotnetCmd = if (userDotnet.exists()) userDotnet.absolutePath else "dotnet"
                lifecycleLog?.i("BLE", "Attempting local compilation of BLE worker via $dotnetCmd...")
                val pb = ProcessBuilder(dotnetCmd, "publish", "-c", "Release", "-r", "win-x64", "--self-contained", "true", csproj.absolutePath)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
                val published = locateWorkerExe()
                if (published != null && published.exists()) return published
            }
        } catch (_: Exception) {}

        // 3. Auto-download prebuilt worker binary
        val downloadUrls = listOf(
            "https://raw.githubusercontent.com/hmm183/DTN-Desktop-App/main/tools/ble-worker/bin/Release/net9.0-windows10.0.19041.0/win-x64/publish/dtn-ble-worker.exe",
            "https://github.com/hmm183/DTN-Desktop-App/releases/latest/download/dtn-ble-worker.exe"
        )
        for (url in downloadUrls) {
            try {
                lifecycleLog?.i("BLE", "Downloading native BLE worker component from $url...")
                if (downloadFile(url, targetWorker)) {
                    lifecycleLog?.i("BLE", "BLE worker successfully downloaded (${targetWorker.length() / 1024} KB).")
                    return targetWorker
                }
            } catch (e: Exception) {
                lifecycleLog?.w("BLE", "Download failed from $url: ${e.message}")
            }
        }

        return locateWorkerExe()
    }

    private fun downloadFile(urlStr: String, destFile: File): Boolean {
        var currentUrl = urlStr
        for (redirect in 0..5) {
            val url = URI.create(currentUrl).toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "DTNDesktopApp/1.0.0")
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location") ?: return false
                currentUrl = loc
                continue
            }
            if (code != 200) return false
            destFile.parentFile?.mkdirs()
            val tmpFile = File(destFile.parentFile, destFile.name + ".tmp")
            conn.inputStream.use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (tmpFile.length() > 50_000L) {
                if (destFile.exists()) destFile.delete()
                return tmpFile.renameTo(destFile)
            } else {
                tmpFile.delete()
                return false
            }
        }
        return false
    }
}
