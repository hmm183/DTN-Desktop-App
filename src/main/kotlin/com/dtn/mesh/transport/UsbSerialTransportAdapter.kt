package com.dtn.mesh.transport

import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.receiver.DtnWireCodec
import com.fazecast.jSerialComm.SerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-speed USB Serial transport for Heltec WiFi LoRa 32 V3 hub connected directly via USB.
 *
 * Speaks the framed protocol: [1B type][2B length BE][payload]
 * - 0x01: REGISTER
 * - 0x02: BUNDLE
 * - 0x03: HELLO
 * - 0x04: PING
 * - 0x05: ROSTER
 * - 0x06: MESH_ROSTER
 */
class UsbSerialTransportAdapter(
    private val localNodeId: NodeId,
    private val onMessageReceived: (DtnMessage, String) -> Unit,
    private val onPeerStatusChanged: (String, Boolean, String) -> Unit, // peerId, online, linkInfo
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectedPortName = MutableStateFlow<String?>(null)
    val connectedPortName: StateFlow<String?> = _connectedPortName.asStateFlow()

    private var activePort: SerialPort? = null
    private var outputStream: OutputStream? = null

    var hubId: NodeId? = null
        private set

    fun start() {
        scope.launch {
            monitorAndConnect()
        }
    }

    fun stop() {
        closePort()
    }

    private suspend fun monitorAndConnect() {
        while (scope.isActive) {
            if (activePort == null || !activePort!!.isOpen) {
                _isConnected.value = false
                _connectedPortName.value = null
                val port = findHeltecPort()
                if (port != null) {
                    tryConnect(port)
                }
            }
            delay(3000)
        }
    }

    private fun findHeltecPort(): SerialPort? {
        val ports = SerialPort.getCommPorts()
        for (p in ports) {
            val desc = (p.portDescription + " " + p.descriptivePortName).lowercase()
            // Heltec V3 typically uses CP210x, CH340, or Espressif USB JTAG/serial
            if (desc.contains("cp210") || desc.contains("ch34") || desc.contains("usb-serial") ||
                desc.contains("espressif") || desc.contains("silicon labs") || desc.contains("usb serial port")
            ) {
                return p
            }
        }
        return ports.firstOrNull() // If only one COM port is available, try it
    }

    private fun tryConnect(port: SerialPort) {
        port.baudRate = 115200
        // Block on read without timing out every second; 0 means infinite wait until data or disconnect
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0)
        port.clearDTR()
        port.clearRTS()
        if (port.openPort()) {
            activePort = port
            outputStream = port.outputStream
            _isConnected.value = true
            _connectedPortName.value = port.systemPortName
            println("[UsbSerial] Connected to LoRa Hub on ${port.systemPortName} (${port.descriptivePortName})")

            // Send REGISTER frame (0x01, len=4, payload=our 32-bit node number)
            sendRegister()

            // Start reading loop
            scope.launch {
                readLoop(port.inputStream)
            }
        }
    }

    private fun sendRegister() {
        val buf = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x01.toByte()) // REGISTER
        buf.putShort(4.toShort())
        buf.putInt(localNodeId.toNodeNum32())
        sendRaw(buf.array())
    }

    fun sendBundle(bundle: DtnMessage): Boolean {
        return try {
            val wire = DtnWireCodec.encode(bundle)
            val frame = ByteBuffer.allocate(3 + wire.size).order(ByteOrder.BIG_ENDIAN)
            frame.put(0x02.toByte()) // BUNDLE
            frame.putShort(wire.size.toShort())
            frame.put(wire)
            sendRaw(frame.array())
            true
        } catch (e: Exception) {
            println("[UsbSerial] Failed to send bundle: ${e.message}")
            false
        }
    }

    @Synchronized
    private fun sendRaw(bytes: ByteArray) {
        try {
            outputStream?.write(bytes)
            outputStream?.flush()
        } catch (e: Exception) {
            println("[UsbSerial] Write error: ${e.message}")
            closePort()
        }
    }

    private suspend fun readLoop(inputStream: InputStream) {
        while (scope.isActive && activePort?.isOpen == true) {
            try {
                // Seek to next valid frame type (0x01..0x06), discarding line noise or boot text
                var firstByte = inputStream.read()
                if (firstByte < 0) {
                    val avail = activePort?.bytesAvailable() ?: -1
                    if (avail < 0) {
                        println("[UsbSerial] Port disconnected")
                        break
                    }
                    delay(50)
                    continue
                }

                while (firstByte !in 0x01..0x06 && scope.isActive && activePort?.isOpen == true) {
                    firstByte = inputStream.read()
                    if (firstByte < 0 && (activePort?.bytesAvailable() ?: -1 < 0)) break
                }
                if (firstByte !in 0x01..0x06) continue

                val type = firstByte

                // Read 2-byte big-endian length
                val lenBuf = ByteArray(2)
                if (!readFully(inputStream, lenBuf, 2)) continue
                val len = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)

                if (len < 0 || len > 2048) {
                    println("[UsbSerial] Frame len $len out of bounds for type 0x%02X, discarding".format(type))
                    continue // Do not disconnect! Discard invalid frame and keep port open
                }

                val payload = ByteArray(len)
                if (!readFully(inputStream, payload, len)) continue

                handleFrame(type, payload)
            } catch (e: Exception) {
                if (activePort?.bytesAvailable() ?: -1 < 0) {
                    println("[UsbSerial] Read error: ${e.message}")
                    break
                }
            }
        }
        closePort()
    }

    private fun handleFrame(type: Int, payload: ByteArray) {
        when (type) {
            0x03 -> { // HELLO from Hub
                if (payload.size >= 4) {
                    val hubNum = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).int
                    val hid = NodeId.fromNodeNum(hubNum)
                    hubId = hid
                    println("[UsbSerial] LoRa Hub identified as $hid")
                    onPeerStatusChanged(hid.value, true, "LoRa Hub (USB)")
                }
            }
            0x02 -> { // BUNDLE
                try {
                    val bundle = DtnWireCodec.decode(payload, rssi = -35, snr = 12f) ?: return
                    onMessageReceived(bundle, hubId?.value ?: "USB_HUB")
                } catch (e: Exception) {
                    println("[UsbSerial] Failed to decode incoming bundle: ${e.message}")
                }
            }
            0x05 -> { // ROSTER
                // Hub roster update: list of phones connected to this hub
                parseRoster(payload)
            }
            0x06 -> { // MESH_ROSTER
                parseRoster(payload)
            }
        }
    }

    private fun parseRoster(payload: ByteArray) {
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        while (bb.remaining() >= 4) {
            val nodeNum = bb.int
            val pid = NodeId.fromNodeNum(nodeNum)
            if (pid != localNodeId) {
                onPeerStatusChanged(pid.value, true, "Online via LoRa Hub")
            }
        }
    }

    private fun readFully(inputStream: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len && scope.isActive && activePort?.isOpen == true) {
            val r = inputStream.read(buf, read, len - read)
            if (r > 0) {
                read += r
            } else if (r < 0) {
                val avail = activePort?.bytesAvailable() ?: -1
                if (avail < 0) return false
                Thread.sleep(20)
            }
        }
        return read == len
    }

    private fun closePort() {
        try {
            outputStream?.close()
            activePort?.closePort()
        } catch (_: Exception) {}
        activePort = null
        outputStream = null
        _isConnected.value = false
        _connectedPortName.value = null
    }
}
