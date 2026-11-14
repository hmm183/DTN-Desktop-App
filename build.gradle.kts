import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

group = "com.dtn.desktop"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Cryptography (Pure Java BouncyCastle)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // USB Serial Communications (Heltec LoRa hub over USB)
    implementation("com.fazecast:jSerialComm:2.11.0")

    // MQTT Internet Relay (HiveMQ)
    implementation("com.hivemq:hivemq-mqtt-client:1.3.3")

    // WebSocket Client (Cloudflare Worker Relay)
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // SQLite Database
    implementation("org.xerial:sqlite-jdbc:3.47.0.0")

    // Windows Native Access (DPAPI keystore, WinRT integration)
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

compose.desktop {
    application {
        mainClass = "com.dtn.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "DTNDesktop"
            packageVersion = "1.0.0"
            description = "DTN Mesh Relay Desktop Command Station"
            copyright = "© 2026 DTN Mesh Team"
            vendor = "DTN Mesh"

            modules(
                "java.base",
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.prefs",
                "java.rmi",
                "java.scripting",
                "java.security.jgss",
                "java.security.sasl",
                "java.sql",
                "java.xml",
                "jdk.crypto.cryptoki",
                "jdk.crypto.ec",
                "jdk.unsupported"
            )

            windows {
                menu = true
                shortcut = true
                dirChooser = true
            }
        }
    }
}
