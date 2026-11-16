package com.dtn.desktop.simulation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Scanner
import java.util.zip.ZipInputStream

data class SimScenarioInfo(
    val id: String,
    val title: String,
    val filename: String,
    val description: String,
    val defaultLambda: Double = 0.5,
    val router: String = "DiMaxPropRouter"
)

data class SimHopStep(
    val timestamp: Double,
    val action: String,
    val fromNode: String,
    val toNode: String,
    val hopNumber: Int,
    val pathSoFar: String
)

data class SimReport(
    val scenarioName: String,
    val executionTimeMs: Long,
    val created: Int,
    val started: Int,
    val relayed: Int,
    val aborted: Int,
    val dropped: Int,
    val delivered: Int,
    val deliveryProbability: Double,
    val overheadRatio: Double,
    val avgLatency: Double,
    val avgHops: Double,
    val finalPath: String,
    val hopSteps: List<SimHopStep>,
    val rawReportText: String
)

class DiMaxPropSimulationService(
    private val appBaseDir: File = File(System.getProperty("user.dir"))
) {
    val simDir: File by lazy {
        val appDataSim = File(System.getProperty("user.home"), ".dtnmesh/sim-dimaxprop")
        val baseDir = try {
            File(DiMaxPropSimulationService::class.java.protectionDomain.codeSource.location.toURI()).parentFile
        } catch (_: Exception) { null }

        val candidates = listOfNotNull(
            File(appBaseDir, "tools/sim-dimaxprop"),
            baseDir?.let { File(it, "tools/sim-dimaxprop") },
            baseDir?.parentFile?.let { File(it, "tools/sim-dimaxprop") },
            baseDir?.parentFile?.let { File(it, "app/tools/sim-dimaxprop") },
            File(appBaseDir, "../tools/sim-dimaxprop"),
            appDataSim,
            File(appBaseDir, "sim-dimaxprop")
        )
        candidates.firstOrNull { it.exists() && File(it, "target/core/DTNSim.class").exists() }
            ?: candidates.firstOrNull { it.exists() }
            ?: File(appBaseDir, "tools/sim-dimaxprop")
    }

    fun isAvailable(): Boolean = File(simDir, "target/core/DTNSim.class").exists()

    suspend fun ensureSimulatorInstalled(onProgress: (String) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (isAvailable()) {
            onProgress("ONE Simulation Engine ready.")
            return@withContext true
        }

        // 1. If simDir exists with src/core/DTNSim.java, compile it!
        val srcFile = File(simDir, "src/core/DTNSim.java")
        if (srcFile.exists()) {
            onProgress("Compiling ONE Simulator classes...")
            val success = compileSimulatorInDir(simDir, onProgress)
            if (success && isAvailable()) {
                onProgress("ONE Simulation Engine ready.")
                return@withContext true
            }
        }

        // 2. If missing, setup in ~/.dtnmesh/sim-dimaxprop
        val appDataSim = File(System.getProperty("user.home"), ".dtnmesh/sim-dimaxprop")
        if (!File(appDataSim, "target/core/DTNSim.class").exists()) {
            onProgress("Downloading ONE simulator package...")
            val downloaded = downloadAndExtractSimulator(appDataSim, onProgress)
            if (downloaded) {
                if (File(appDataSim, "src/core/DTNSim.java").exists() && !File(appDataSim, "target/core/DTNSim.class").exists()) {
                    onProgress("Compiling downloaded simulator classes...")
                    compileSimulatorInDir(appDataSim, onProgress)
                }
            }
        }

        val ready = isAvailable() || File(appDataSim, "target/core/DTNSim.class").exists()
        if (ready) {
            onProgress("ONE Simulation Engine ready.")
        } else {
            onProgress("Simulation Engine compilation failed.")
        }
        ready
    }

    private fun compileSimulatorInDir(dir: File, onProgress: (String) -> Unit): Boolean {
        return try {
            val compileBat = File(dir, "compile.bat")
            if (System.getProperty("os.name").lowercase().contains("windows") && compileBat.exists()) {
                onProgress("Running compile.bat...")
                val pb = ProcessBuilder("cmd.exe", "/c", "compile.bat")
                pb.directory(dir)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
                File(dir, "target/core/DTNSim.class").exists()
            } else {
                onProgress("Compiling Java sources...")
                val targetDir = File(dir, "target").apply { mkdirs() }
                val javacCmd = findJavacCommand()
                val libCp = "lib/ECLA.jar${File.pathSeparator}lib/DTNConsoleConnection.jar"
                val javaFiles = dir.resolve("src").walkTopDown().filter { it.extension == "java" }.map { it.absolutePath }.toList()
                if (javaFiles.isEmpty()) return false
                val args = mutableListOf(javacCmd, "-encoding", "UTF-8", "-sourcepath", "src", "-d", targetDir.absolutePath, "-cp", libCp)
                args.addAll(javaFiles)
                val pb = ProcessBuilder(args)
                pb.directory(dir)
                val proc = pb.start()
                proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
                File(dir, "target/core/DTNSim.class").exists()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun downloadAndExtractSimulator(destDir: File, onProgress: (String) -> Unit): Boolean {
        destDir.mkdirs()
        val zipUrls = listOf(
            "https://raw.githubusercontent.com/hmm183/DTN-Desktop-App/main/tools/sim-dimaxprop.zip",
            "https://github.com/hmm183/DTN-Desktop-App/releases/latest/download/sim-dimaxprop.zip"
        )
        for (urlStr in zipUrls) {
            try {
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
                        val loc = conn.getHeaderField("Location") ?: break
                        currentUrl = loc
                        continue
                    }
                    if (code != 200) break

                    ZipInputStream(conn.inputStream).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val file = File(destDir, entry.name)
                            if (entry.isDirectory) {
                                file.mkdirs()
                            } else {
                                file.parentFile?.mkdirs()
                                FileOutputStream(file).use { out -> zis.copyTo(out) }
                            }
                            entry = zis.nextEntry
                        }
                    }
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    fun listScenarios(): List<SimScenarioInfo> = listOf(
        SimScenarioInfo(
            id = "single_dimaxprop",
            title = "DiMaxProp Single Message Path",
            filename = "DiMaxProp_SingleMessagePath_settings.txt",
            description = "Traces a single bundle hop-by-hop with geographic distance-weighted Dijkstra routing.",
            defaultLambda = 0.5,
            router = "DiMaxPropRouter"
        ),
        SimScenarioInfo(
            id = "single_trace",
            title = "Single Message Hop Trace (Standard)",
            filename = "SingleMessagePath_settings.txt",
            description = "Traces message transmission from source node p0 to destination c5 across Helsinki.",
            defaultLambda = 0.5,
            router = "MaxPropRouter"
        ),
        SimScenarioInfo(
            id = "dimaxprop_mesh",
            title = "DiMaxProp Full Helsinki Mesh",
            filename = "DiMaxProp_settings.txt",
            description = "100 mobile nodes running DiMaxProp router evaluating delivery ratio and contact frequencies.",
            defaultLambda = 0.5,
            router = "DiMaxPropRouter"
        ),
        SimScenarioInfo(
            id = "dimaxprop_lifecycle",
            title = "DiMaxProp Lifecycle & Buffer Dynamics",
            filename = "DiMaxProp_Lifecycle_settings.txt",
            description = "Evaluates buffer occupancy, TTL decay, and message forward/abort transitions.",
            defaultLambda = 0.5,
            router = "DiMaxPropRouter"
        ),
        SimScenarioInfo(
            id = "prophet_benchmark",
            title = "Improved PRoPHET RFC 6693",
            filename = "ImprovedProphet_settings.txt",
            description = "Standard PRoPHET delivery predictability benchmark for comparison with DiMaxProp.",
            defaultLambda = 0.0,
            router = "ProphetRouter"
        ),
        SimScenarioInfo(
            id = "default_helsinki",
            title = "Standard Multi-Group Helsinki Mesh",
            filename = "default_settings.txt",
            description = "Classic Helsinki city scenario with pedestrians, cars, and trams.",
            defaultLambda = 0.5,
            router = "MaxPropRouter"
        )
    )

    fun launchInteractiveVisualizer(scenarioFilename: String): Process? {
        if (!isAvailable()) return null
        val javaCmd = findJavaCommand()
        val pb = ProcessBuilder(
            javaCmd,
            "-Xmx512M",
            "-cp",
            "target;lib/ECLA.jar;lib/DTNConsoleConnection.jar",
            "core.DTNSim",
            scenarioFilename
        )
        pb.directory(simDir)
        pb.redirectErrorStream(true)
        return pb.start()
    }

    suspend fun runBenchmark(
        scenarioFilename: String,
        lambda: Double = 0.5,
        alpha: Double = 1.0,
        bufferMb: Int = 5,
        onProgress: (String) -> Unit = {}
    ): SimReport = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        onProgress("Preparing simulation environment...")

        val baseScenarioFile = File(simDir, scenarioFilename)
        val configFileToRun: String

        if (scenarioFilename.contains("DiMaxProp", ignoreCase = true) || scenarioFilename.contains("SingleMessage", ignoreCase = true)) {
            // Write a customized scenario with user's lambda and buffer parameters
            val customConfigFile = File(simDir, "runtime_custom_settings.txt")
            val baseContent = if (baseScenarioFile.exists()) baseScenarioFile.readText() else ""
            val modifiedContent = buildString {
                appendLine(baseContent)
                appendLine()
                appendLine("# --- User Overrides from Compose Desktop UI ---")
                appendLine("Group.lambda = $lambda")
                appendLine("Group.alpha = $alpha")
                appendLine("Group.bufferSize = ${bufferMb}M")
            }
            customConfigFile.writeText(modifiedContent)
            configFileToRun = "runtime_custom_settings.txt"
        } else {
            configFileToRun = scenarioFilename
        }

        onProgress("Executing simulation engine (headlessly)...")
        val javaCmd = findJavaCommand()
        val pb = ProcessBuilder(
            javaCmd,
            "-Xmx512M",
            "-cp",
            "target;lib/ECLA.jar;lib/DTNConsoleConnection.jar",
            "core.DTNSim",
            "-b",
            "1",
            configFileToRun
        )
        pb.directory(simDir)
        pb.redirectErrorStream(true)

        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        val elapsedMs = System.currentTimeMillis() - startMs
        onProgress("Parsing simulation reports...")

        // Scan reports directory
        val reportsDir = File(simDir, "reports")
        val statsFiles = reportsDir.listFiles { f -> f.name.endsWith("MessageStatsReport.txt") }
        val latestStats = statsFiles?.maxByOrNull { it.lastModified() }
        val pathFiles = reportsDir.listFiles { f -> f.name.endsWith("SingleMessagePathReport.txt") }
        val latestPath = pathFiles?.maxByOrNull { it.lastModified() }

        parseReports(latestStats, latestPath, output, elapsedMs, scenarioFilename)
    }

    private fun parseReports(
        statsFile: File?,
        pathFile: File?,
        rawConsoleOutput: String,
        elapsedMs: Long,
        scenarioName: String
    ): SimReport {
        var created = 0
        var started = 0
        var relayed = 0
        var aborted = 0
        var dropped = 0
        var delivered = 0
        var deliveryProb = 0.0
        var overhead = 0.0
        var latencyAvg = 0.0
        var hopsAvg = 0.0

        val statsText = statsFile?.takeIf { it.exists() }?.readText().orEmpty()
        val scanner = Scanner(statsText)
        while (scanner.hasNextLine()) {
            val line = scanner.nextLine().trim()
            val parts = line.split(":")
            if (parts.size >= 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                when (key) {
                    "created" -> created = value.toIntOrNull() ?: created
                    "started" -> started = value.toIntOrNull() ?: started
                    "relayed" -> relayed = value.toIntOrNull() ?: relayed
                    "aborted" -> aborted = value.toIntOrNull() ?: aborted
                    "dropped" -> dropped = value.toIntOrNull() ?: dropped
                    "delivered" -> delivered = value.toIntOrNull() ?: delivered
                    "delivery_prob" -> deliveryProb = value.toDoubleOrNull() ?: deliveryProb
                    "overhead_ratio" -> overhead = value.toDoubleOrNull() ?: overhead
                    "latency_avg" -> latencyAvg = value.toDoubleOrNull() ?: latencyAvg
                    "hopcount_avg" -> hopsAvg = value.toDoubleOrNull() ?: hopsAvg
                }
            }
        }

        // Parse path hops if single message scenario
        val hopSteps = mutableListOf<SimHopStep>()
        var finalPath = ""
        val pathText = pathFile?.takeIf { it.exists() }?.readText().orEmpty()
        if (pathText.isNotBlank()) {
            val lines = pathText.lines()
            for (line in lines) {
                if (line.contains("Final path  :")) {
                    finalPath = line.substringAfter("Final path  :").trim()
                } else if (line.contains("TRANSFER STARTED")) {
                    val time = line.substringBefore("]").substringAfter("[").toDoubleOrNull() ?: 0.0
                    val transfer = line.substringAfter("|").substringAfter("|").trim()
                    val nodes = transfer.split("->").map { it.trim() }
                    if (nodes.size >= 2) {
                        hopSteps.add(SimHopStep(time, "TRANSFER STARTED", nodes[0], nodes[1], hopSteps.size + 1, transfer))
                    }
                } else if (line.contains("DESTINATION REACHED")) {
                    // reached mark
                }
            }
        }

        val combinedReport = buildString {
            appendLine("=== DTN SIMULATION RUN SUMMARY ===")
            appendLine("Scenario: $scenarioName")
            appendLine("Wall Clock Execution Time: ${elapsedMs}ms")
            appendLine("Delivery Probability: ${(deliveryProb * 100).format(1)}%")
            appendLine("Created: $created | Relayed: $relayed | Delivered: $delivered | Aborted: $aborted")
            appendLine("Average Latency: ${latencyAvg.format(2)}s | Average Hops: ${hopsAvg.format(1)}")
            appendLine("Overhead Ratio: ${overhead.format(2)}")
            if (finalPath.isNotBlank()) {
                appendLine("Final Discovered Route: $finalPath")
            }
            appendLine()
            appendLine("--- Raw Engine Output ---")
            appendLine(rawConsoleOutput.trim())
            if (statsText.isNotBlank()) {
                appendLine()
                appendLine("--- Message Stats Report ---")
                appendLine(statsText.trim())
            }
        }

        return SimReport(
            scenarioName = scenarioName,
            executionTimeMs = elapsedMs,
            created = created,
            started = started,
            relayed = relayed,
            aborted = aborted,
            dropped = dropped,
            delivered = delivered,
            deliveryProbability = deliveryProb,
            overheadRatio = overhead,
            avgLatency = latencyAvg,
            avgHops = hopsAvg,
            finalPath = finalPath,
            hopSteps = hopSteps,
            rawReportText = combinedReport
        )
    }

    private fun Double.format(decimals: Int): String = String.format(java.util.Locale.US, "%.${decimals}f", this)

    private fun findJavaCommand(): String {
        val javaHome = System.getProperty("java.home")
        if (javaHome != null) {
            val exe = File(javaHome, "bin/java.exe")
            if (exe.exists()) return exe.absolutePath
            val bin = File(javaHome, "bin/java")
            if (bin.exists()) return bin.absolutePath
        }
        return "java"
    }

    private fun findJavacCommand(): String {
        val javaHome = System.getProperty("java.home")
        if (javaHome != null) {
            val exe = File(javaHome, "bin/javac.exe")
            if (exe.exists()) return exe.absolutePath
            val parent = File(javaHome, "../bin/javac.exe")
            if (parent.exists()) return parent.canonicalPath
        }
        val pathDirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
        for (dir in pathDirs) {
            val f = File(dir, "javac.exe")
            if (f.exists()) return f.absolutePath
        }
        return "javac"
    }
}
