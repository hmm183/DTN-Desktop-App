package com.dtn.desktop.notification

import java.awt.Toolkit
import java.io.File

/**
 * Native Windows Desktop Toast Notification Service.
 *
 * Dispatches native Windows 10/11 Action Center toast notifications with audio chimes.
 */
object DesktopNotifier {

    fun init() {
        // Pre-create the notification script in the user's DTN directory
        ensureScriptFile()
    }

    /**
     * Display a native desktop toast notification to the user.
     */
    fun notify(title: String, message: String) {
        // 1. Play immediate audio feedback
        try {
            Toolkit.getDefaultToolkit().beep()
        } catch (_: Exception) {}

        // 2. Dispatch native Windows 10/11 Toast Notification via WinRT
        try {
            val scriptFile = ensureScriptFile()
            val safeTitle = title.replace("\r", " ").replace("\n", " ").trim()
            val safeMsg = message.replace("\r", " ").replace("\n", " ").trim()

            val pb = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden",
                "-File", scriptFile.absolutePath,
                "-title", safeTitle,
                "-msg", safeMsg
            )
            pb.redirectErrorStream(true)
            pb.start()
        } catch (_: Exception) {}
    }

    private fun ensureScriptFile(): File {
        val binDir = File(System.getProperty("user.home"), ".dtnmesh/bin").apply { mkdirs() }
        val scriptFile = File(binDir, "toast_notify.ps1")
        if (!scriptFile.exists() || scriptFile.length() < 100L) {
            scriptFile.writeText("""
param(
    [Parameter(Mandatory=${'$'}true)][string]${'$'}title,
    [Parameter(Mandatory=${'$'}true)][string]${'$'}msg
)
try {
    [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
    ${'$'}template = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)
    ${'$'}textNodes = ${'$'}template.GetElementsByTagName("text")
    ${'$'}node0 = ${'$'}template.CreateTextNode(${'$'}title)
    ${'$'}textNodes.Item(0).AppendChild(${'$'}node0) | Out-Null
    ${'$'}node1 = ${'$'}template.CreateTextNode(${'$'}msg)
    ${'$'}textNodes.Item(1).AppendChild(${'$'}node1) | Out-Null
    ${'$'}toast = [Windows.UI.Notifications.ToastNotification]::new(${'$'}template)
    ${'$'}appId = "{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\WindowsPowerShell\v1.0\powershell.exe"
    [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(${'$'}appId).Show(${'$'}toast)
    [System.Media.SystemSounds]::Asterisk.Play()
} catch {}
""".trimIndent())
        }
        return scriptFile
    }
}
