# DTN Mesh Relay — Desktop Command Station

A native Windows desktop application (`.exe`) for the **Delay-Tolerant Networking (DTN) Mesh Relay** communication system. 

It acts as a **Mesh Command Center / Base Station**, enabling PCs and field laptops to monitor real-time message routing, inspect queues, track network topology, and exchange end-to-end encrypted messages with Android phones over heterogeneous mesh radios and cloud relays.

---

## Features

### 1. Heterogeneous Multi-Transport Layer
* **USB Serial LoRa Bridge (`UsbSerialTransportAdapter`)**: Auto-detects Heltec WiFi LoRa 32 V3 boards connected via USB COM port using `jSerialComm` (115200 baud). High-speed, tethered LoRa backbone integration.
* **Wi-Fi SoftAP LoRa Bridge (`TcpLoRaHubTransportAdapter`)**: Connects to the ESP32 LoRa hub SoftAP (`DTN-HUB-XXXX`) over TCP port `9740`.
* **Bluetooth Low Energy (BLE) (`WindowsBleTransportAdapter`)**: Windows BLE Scanner & GATT Server integration for P2P links with nearby phones.
* **Wi-Fi Direct (`WindowsWifiDirectTransportAdapter`)**: Listens on TCP port `9734` for high-throughput queue synchronization.
* **Dual Internet Relays (`DesktopInternetTransportAdapter`)**: Parallel HiveMQ MQTT (`broker.hivemq.com:8883`) + Cloudflare Worker WebSocket relay (`wss://dtnmesh.vrishankrainaphotos1.workers.dev/ws`).

### 2. Intelligent Routing & Decision Engines
Includes all 6 routing strategies:
1. **Epidemic**: Controlled flooding with cache deduplication.
2. **PRoPHET (RFC 6693)**: Delivery predictability with delta-capping ($1 - \delta = 0.99$) and adaptive encounter scaling.
3. **STABLE-PROPHET**: Link-quality and RSSI weighted predictability.
4. **MaxProp (INFOCOM 2006)**: Dijkstra shortest-path over encounter cost edges $(1 - f)$.
5. **DiMaxProp**: Distance-weighted Dijkstra hop costs $(1 - f) + \lambda \cdot d_{norm}$.
6. **Double Q-Learning**: 9-dimensional state reinforcement learning kernel for `FORWARD` vs `STORE_WAIT`.

### 3. Security & Cryptography
* **End-to-End Encryption (E2EE)**: X25519 ECDH + AES-256-GCM authenticated encryption via BouncyCastle.
* **Keystore Protection**: Private identity key wrapped at rest using Windows DPAPI (tied to Windows user login credentials).
* **Cryptographic Identity**: Ed25519 signed `ANNOUNCE` packets.

### 4. Desktop-Optimized Compose UI
* **3-Pane Widescreen Dashboard**:
  * **Top Bar**: Live status pills for all 5 radios (USB, Wi-Fi Hub, BLE, Wi-Fi Direct, Internet Relay).
  * **Left Rail**: Chats list, Queue counter, Network Topology map, and Diagnostics Console.
  * **Center**: Full chat conversation view with status ticks (`🕓 BUFFERED` $\to$ `✓ SENT` $\to$ `✓✓ DELIVERED`).
  * **Right**: Node inspector with safety numbers, PRoPHET probabilities, and link details.

---

## Building and Running

### Prerequisites
* JDK 17 or higher
* Windows 10/11

### Run directly from source
```powershell
./gradlew run
```

### Run all Unit Tests (91 tests)
```powershell
./gradlew test
```

### Build Standalone Executable (`DTNDesktop.exe`)
```powershell
./gradlew createDistributable
```
The standalone application folder is generated at:
`build/compose/binaries/main/app/DTNDesktop/DTNDesktop.exe`

### Build Windows MSI Installer
```powershell
./gradlew packageMsi
```
The installer is generated at:
`build/compose/binaries/main/msi/DTNDesktop-1.0.0.msi`
