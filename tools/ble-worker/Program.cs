using System;
using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

namespace Dtn.BleWorker;

public class Program
{
    private static readonly Guid ServiceUuid = Guid.Parse("d791b000-2c8f-4f2a-9b1e-000000000001");
    private static readonly Guid BundleCharUuid = Guid.Parse("d791b001-2c8f-4f2a-9b1e-000000000001");
    private static readonly Guid LinkCharUuid = Guid.Parse("d791b002-2c8f-4f2a-9b1e-000000000001");

    private static BluetoothLEAdvertisementWatcher? _watcher;
    private static BluetoothLEAdvertisementPublisher? _advPublisher;
    private static GattServiceProvider? _serviceProvider;
    private static GattLocalCharacteristic? _bundleCharacteristic;
    private static GattLocalCharacteristic? _linkCharacteristic;

    private class RxBuffer
    {
        public int TotalLength;
        public MemoryStream Stream = new();
    }
    private static readonly ConcurrentDictionary<string, RxBuffer> _rxBuffers = new();
    private static readonly ConcurrentDictionary<string, BluetoothLEDevice> _connectedCentrals = new();
    private static readonly ConcurrentDictionary<string, GattCharacteristic> _activeBundleChars = new();
    private static readonly ConcurrentDictionary<string, GattCharacteristic> _activeLinkChars = new();
    private static readonly ConcurrentDictionary<string, long> _lastLinkAttempt = new();
    private static readonly ConcurrentDictionary<string, string> _macToNodeId = new();
    private static readonly ConcurrentDictionary<string, string> _nodeIdToMac = new();

    private static int _localNodeNum = 0;
    private static string _localNodeId = "";
    private static string? _centralPeerId = null;

    public static async Task Main(string[] args)
    {
        Console.OutputEncoding = Encoding.UTF8;
        Console.InputEncoding = Encoding.UTF8;

        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] == "--node-num" && i + 1 < args.Length)
            {
                int.TryParse(args[i + 1], out _localNodeNum);
            }
            else if (args[i] == "--node-id" && i + 1 < args.Length)
            {
                _localNodeId = args[i + 1];
            }
        }

        try
        {
            await InitGattServerAsync();
            StartBleAdvertising();
            InitScanner();
            EmitJson("status", new JsonObject
            {
                ["available"] = true,
                ["message"] = "BLE GATT Server & Active Scanner active"
            });
        }
        catch (Exception ex)
        {
            EmitJson("status", new JsonObject
            {
                ["available"] = false,
                ["message"] = ex.Message
            });
        }

        // Stdin command processing loop
        var readerTask = Task.Run(async () =>
        {
            string? line;
            while ((line = await Console.In.ReadLineAsync()) != null)
            {
                line = line.Trim();
                if (string.IsNullOrEmpty(line)) continue;

                try
                {
                    var node = JsonNode.Parse(line)?.AsObject();
                    if (node == null) continue;

                    string cmd = node["cmd"]?.ToString() ?? "";
                    if (cmd == "send")
                    {
                        string mac = node["mac"]?.ToString() ?? "";
                        string wireBase64 = node["wireBase64"]?.ToString() ?? "";
                        string nodeId = node["nodeId"]?.ToString() ?? "";
                        if (string.IsNullOrEmpty(mac) && !string.IsNullOrEmpty(nodeId))
                        {
                            mac = _nodeIdToMac.GetValueOrDefault(nodeId, "");
                        }
                        _ = SendBundleToPeerAsync(mac, nodeId, wireBase64);
                    }
                    else if (cmd == "connect")
                    {
                        string mac = node["mac"]?.ToString() ?? "";
                        if (!string.IsNullOrEmpty(mac) && mac.Contains(":"))
                        {
                            ulong addr = ParseMac(mac);
                            _ = ConnectAndLinkAsync(addr, mac, force: true);
                        }
                    }
                    else if (cmd == "stop")
                    {
                        break;
                    }
                }
                catch (Exception ex)
                {
                    EmitJson("error", new JsonObject { ["error"] = ex.Message });
                }
            }
        });

        await readerTask;
        Cleanup();
    }

    private static async Task InitGattServerAsync()
    {
        var result = await GattServiceProvider.CreateAsync(ServiceUuid);
        if (result.Error != BluetoothError.Success)
        {
            throw new InvalidOperationException($"GattServiceProvider.CreateAsync failed: {result.Error}");
        }

        _serviceProvider = result.ServiceProvider;

        // 1. Bundle characteristic (for incoming writes from phones)
        var bundleParams = new GattLocalCharacteristicParameters
        {
            CharacteristicProperties = GattCharacteristicProperties.Write | GattCharacteristicProperties.WriteWithoutResponse,
            WriteProtectionLevel = GattProtectionLevel.Plain
        };
        var bundleRes = await _serviceProvider.Service.CreateCharacteristicAsync(BundleCharUuid, bundleParams);
        if (bundleRes.Error != BluetoothError.Success)
        {
            throw new InvalidOperationException($"CreateCharacteristicAsync BundleChar failed: {bundleRes.Error}");
        }
        _bundleCharacteristic = bundleRes.Characteristic;
        _bundleCharacteristic.WriteRequested += OnBundleWriteRequested;

        // 2. Link characteristic (for incoming hello/subscriptions from phones)
        var linkParams = new GattLocalCharacteristicParameters
        {
            CharacteristicProperties = GattCharacteristicProperties.Indicate | GattCharacteristicProperties.Notify | GattCharacteristicProperties.Write,
            WriteProtectionLevel = GattProtectionLevel.Plain
        };
        var linkRes = await _serviceProvider.Service.CreateCharacteristicAsync(LinkCharUuid, linkParams);
        if (linkRes.Error != BluetoothError.Success)
        {
            throw new InvalidOperationException($"CreateCharacteristicAsync LinkChar failed: {linkRes.Error}");
        }
        _linkCharacteristic = linkRes.Characteristic;
        _linkCharacteristic.WriteRequested += async (s, args) =>
        {
            var deferral = args.GetDeferral();
            try
            {
                var request = await args.GetRequestAsync();
                var reader = DataReader.FromBuffer(request.Value);
                byte[] data = new byte[reader.UnconsumedBufferLength];
                reader.ReadBytes(data);

                // Check for BleHello from phone: [0x01][4B nodeNum BE]
                if (data.Length >= 5 && data[0] == 0x01)
                {
                    int peerNum = BinaryPrimitives.ReadInt32BigEndian(data.AsSpan(1, 4));
                    string peerId = "!" + ((uint)peerNum).ToString("x8");
                    _centralPeerId = peerId;
                    EmitJson("discovered", new JsonObject
                    {
                        ["nodeId"] = peerId,
                        ["mac"] = "GATT_CENTRAL",
                        ["rssi"] = -50
                    });
                }

                if (request.Option == GattWriteOption.WriteWithResponse)
                {
                    request.Respond();
                }
            }
            finally
            {
                deferral.Complete();
            }
        };

        // 3. Start advertising GATT service
        var advParams = new GattServiceProviderAdvertisingParameters
        {
            IsConnectable = true,
            IsDiscoverable = true
        };
        _serviceProvider.StartAdvertising(advParams);
    }

    private static void StartBleAdvertising()
    {
        try
        {
            _advPublisher = new BluetoothLEAdvertisementPublisher();
            _advPublisher.Advertisement.ServiceUuids.Add(ServiceUuid);

            // 128-bit Service Data (DataType 0x21):
            // 16 bytes Service UUID (in reverse byte order for BLE GAP) + 4 bytes localNodeNum (Big Endian)
            // Target UUID: d791b000-2c8f-4f2a-9b1e-000000000001
            byte[] uuidReversed = new byte[16]
            {
                0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1E, 0x9B,
                0x2A, 0x4F, 0x8F, 0x2C, 0x00, 0xB0, 0x91, 0xD7
            };
            byte[] payload = new byte[20];
            Array.Copy(uuidReversed, 0, payload, 0, 16);
            BinaryPrimitives.WriteInt32BigEndian(payload.AsSpan(16, 4), _localNodeNum);

            var writer = new DataWriter();
            writer.WriteBytes(payload);
            var section = new BluetoothLEAdvertisementDataSection(0x21, writer.DetachBuffer());
            _advPublisher.Advertisement.DataSections.Add(section);

            _advPublisher.Start();
            EmitJson("log", new JsonObject
            {
                ["source"] = "BLE",
                ["level"] = "INFO",
                ["text"] = $"BluetoothLEAdvertisementPublisher active: ServiceData broadcast nodeNum=0x{_localNodeNum:X8}"
            });
        }
        catch (Exception ex)
        {
            EmitJson("log", new JsonObject
            {
                ["source"] = "BLE",
                ["level"] = "WARN",
                ["text"] = $"BluetoothLEAdvertisementPublisher fallback: {ex.Message}"
            });
        }
    }

    private static async void OnBundleWriteRequested(GattLocalCharacteristic sender, GattWriteRequestedEventArgs args)
    {
        var deferral = args.GetDeferral();
        GattWriteRequest? request = null;
        try
        {
            request = await args.GetRequestAsync();
            var reader = DataReader.FromBuffer(request.Value);
            byte[] data = new byte[reader.UnconsumedBufferLength];
            reader.ReadBytes(data);

            string sessionKey = "server_rx";
            ProcessIncomingChunk(sessionKey, data, -50);

            if (request.Option == GattWriteOption.WriteWithResponse)
            {
                request.Respond();
            }
        }
        catch (Exception ex)
        {
            try
            {
                if (request != null && request.Option == GattWriteOption.WriteWithResponse)
                {
                    request.RespondWithProtocolError(GattProtocolError.UnlikelyError);
                }
            }
            catch { }
            EmitJson("error", new JsonObject { ["error"] = ex.Message });
        }
        finally
        {
            deferral.Complete();
        }
    }

    private static void InitScanner()
    {
        _watcher = new BluetoothLEAdvertisementWatcher
        {
            ScanningMode = BluetoothLEScanningMode.Active
        };

        _watcher.Received += (s, e) =>
        {
            try
            {
                bool hasService = e.Advertisement.ServiceUuids.Contains(ServiceUuid);
                int? nodeNum = null;

                // Check DataSections for ServiceData (0x16, 0x20, 0x21)
                foreach (var section in e.Advertisement.DataSections)
                {
                    var reader = DataReader.FromBuffer(section.Data);
                    byte[] bytes = new byte[reader.UnconsumedBufferLength];
                    reader.ReadBytes(bytes);

                    if (section.DataType == 0x21 && bytes.Length >= 20)
                    {
                        // 128-bit Service Data: 16 bytes UUID + 4 bytes nodeNum32
                        nodeNum = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(16, 4));
                        hasService = true;
                        break;
                    }
                    else if (section.DataType == 0x16 && bytes.Length >= 6)
                    {
                        // 16-bit Service Data: 2 bytes UUID + 4 bytes nodeNum32
                        nodeNum = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(2, 4));
                        hasService = true;
                        break;
                    }
                    else if (bytes.Length >= 4 && (section.DataType == 0x21 || section.DataType == 0x16))
                    {
                        if (bytes.Length >= 20)
                        {
                            nodeNum = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(16, 4));
                        }
                        else
                        {
                            nodeNum = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(0, 4));
                        }
                        hasService = true;
                        break;
                    }
                }

                ulong addr = e.BluetoothAddress;
                string mac = FormatMac(addr);

                if (hasService)
                {
                    string nodeId = nodeNum.HasValue ? "!" + ((uint)nodeNum.Value).ToString("x8") : "!ble_" + mac.Replace(":", "").ToLower();
                    _macToNodeId[mac] = nodeId;
                    _nodeIdToMac[nodeId] = mac;

                    EmitJson("discovered", new JsonObject
                    {
                        ["nodeId"] = nodeId,
                        ["mac"] = mac,
                        ["rssi"] = e.RawSignalStrengthInDBm
                    });

                    // Active Central mode: initiate connection to phone if not already linked
                    long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                    long lastAttempt = _lastLinkAttempt.GetValueOrDefault(mac, 0L);
                    if (!_connectedCentrals.ContainsKey(mac) && (now - lastAttempt > 8_000L))
                    {
                        _lastLinkAttempt[mac] = now;
                        _ = ConnectAndLinkAsync(addr, mac, force: false);
                    }
                }
            }
            catch { }
        };

        _watcher.Start();
    }

    private static async Task ConnectAndLinkAsync(ulong addr, string mac, bool force)
    {
        try
        {
            var device = await BluetoothLEDevice.FromBluetoothAddressAsync(addr);
            if (device == null) return;

            var servicesRes = await device.GetGattServicesForUuidAsync(ServiceUuid);
            if (servicesRes.Status != GattCommunicationStatus.Success || servicesRes.Services.Count == 0) return;

            var service = servicesRes.Services[0];

            // 1. Link Characteristic: subscribe to Indications and send BleHello
            var linkRes = await service.GetCharacteristicsForUuidAsync(LinkCharUuid);
            if (linkRes.Status == GattCommunicationStatus.Success && linkRes.Characteristics.Count > 0)
            {
                var linkChar = linkRes.Characteristics[0];

                // Subscribe to indications for incoming bundles from phone
                await linkChar.WriteClientCharacteristicConfigurationDescriptorWithResultAsync(
                    GattClientCharacteristicConfigurationDescriptorValue.Indicate
                );

                linkChar.ValueChanged += (s, e) =>
                {
                    try
                    {
                        var reader = DataReader.FromBuffer(e.CharacteristicValue);
                        byte[] data = new byte[reader.UnconsumedBufferLength];
                        reader.ReadBytes(data);
                        ProcessIncomingChunk(mac, data, -55);
                    }
                    catch { }
                };

                // Write BleHello: [0x01][4B localNodeNum BE] in Big Endian
                var writer = new DataWriter();
                writer.ByteOrder = ByteOrder.BigEndian;
                writer.WriteByte(0x01); // VERSION 1
                writer.WriteInt32(_localNodeNum);
                await linkChar.WriteValueWithResultAsync(writer.DetachBuffer(), GattWriteOption.WriteWithResponse);

                _activeLinkChars[mac] = linkChar;
            }

            // 2. Bundle Characteristic: store for outgoing bundle writes
            var bundleRes = await service.GetCharacteristicsForUuidAsync(BundleCharUuid);
            if (bundleRes.Status == GattCommunicationStatus.Success && bundleRes.Characteristics.Count > 0)
            {
                _activeBundleChars[mac] = bundleRes.Characteristics[0];
            }

            _connectedCentrals[mac] = device;

            EmitJson("linkUp", new JsonObject
            {
                ["mac"] = mac,
                ["success"] = true
            });
        }
        catch (Exception ex)
        {
            EmitJson("error", new JsonObject { ["error"] = $"ConnectAndLinkAsync ({mac}): {ex.Message}" });
        }
    }

    private static void ProcessIncomingChunk(string sessionKey, byte[] data, int rssi)
    {
        if (data.Length < 2) return;
        var rx = _rxBuffers.GetOrAdd(sessionKey, _ => new RxBuffer());
        lock (rx)
        {
            if (rx.TotalLength == 0)
            {
                // First chunk: [2B totalLen BE][bytes...]
                rx.TotalLength = BinaryPrimitives.ReadUInt16BigEndian(data);
                rx.Stream.SetLength(0);
                if (data.Length > 2)
                {
                    rx.Stream.Write(data, 2, data.Length - 2);
                }
            }
            else
            {
                // Subsequent chunk
                rx.Stream.Write(data, 0, data.Length);
            }

            if (rx.Stream.Length >= rx.TotalLength && rx.TotalLength > 0)
            {
                byte[] completeWire = rx.Stream.ToArray();
                rx.TotalLength = 0;
                rx.Stream.SetLength(0);

                EmitJson("bundle", new JsonObject
                {
                    ["wireBase64"] = Convert.ToBase64String(completeWire),
                    ["rssi"] = rssi
                });
            }
        }
    }

    private static async Task SendBundleToPeerAsync(string mac, string nodeId, string wireBase64)
    {
        try
        {
            byte[] wireBytes = Convert.FromBase64String(wireBase64);

            // If target is GATT_CENTRAL or we don't have a MAC address, send via our GATT server LinkChar indication
            if (mac == "GATT_CENTRAL" || string.IsNullOrEmpty(mac) || !mac.Contains(":"))
            {
                if (_linkCharacteristic != null)
                {
                    byte[] framed = new byte[2 + wireBytes.Length];
                    BinaryPrimitives.WriteUInt16BigEndian(framed, (ushort)wireBytes.Length);
                    Array.Copy(wireBytes, 0, framed, 2, wireBytes.Length);

                    const int chunkSize = 240;
                    int offset = 0;
                    while (offset < framed.Length)
                    {
                        int len = Math.Min(chunkSize, framed.Length - offset);
                        var writer = new DataWriter();
                        var slice = new byte[len];
                        Array.Copy(framed, offset, slice, 0, len);
                        writer.WriteBytes(slice);

                        await _linkCharacteristic.NotifyValueAsync(writer.DetachBuffer());
                        offset += len;
                    }

                    EmitJson("sent", new JsonObject
                    {
                        ["nodeId"] = nodeId,
                        ["mac"] = mac,
                        ["success"] = true
                    });
                    return;
                }
            }

            // Otherwise, laptop is Central: write to the peripheral's Bundle Characteristic
            GattCharacteristic? characteristic = _activeBundleChars.GetValueOrDefault(mac);

            if (characteristic == null && mac.Contains(":"))
            {
                ulong address = ParseMac(mac);
                var device = await BluetoothLEDevice.FromBluetoothAddressAsync(address);
                if (device == null)
                {
                    EmitJson("sent", new JsonObject
                    {
                        ["nodeId"] = nodeId,
                        ["mac"] = mac,
                        ["success"] = false,
                        ["error"] = "Device could not be opened"
                    });
                    return;
                }

                var gattServices = await device.GetGattServicesForUuidAsync(ServiceUuid);
                if (gattServices.Status != GattCommunicationStatus.Success || gattServices.Services.Count == 0)
                {
                    EmitJson("sent", new JsonObject
                    {
                        ["nodeId"] = nodeId,
                        ["mac"] = mac,
                        ["success"] = false,
                        ["error"] = "DTN Service not found on device"
                    });
                    return;
                }

                var service = gattServices.Services[0];
                var charResult = await service.GetCharacteristicsForUuidAsync(BundleCharUuid);
                if (charResult.Status != GattCommunicationStatus.Success || charResult.Characteristics.Count == 0)
                {
                    EmitJson("sent", new JsonObject
                    {
                        ["nodeId"] = nodeId,
                        ["mac"] = mac,
                        ["success"] = false,
                        ["error"] = "Bundle Characteristic not found on device"
                    });
                    return;
                }

                characteristic = charResult.Characteristics[0];
                _activeBundleChars[mac] = characteristic;
                _connectedCentrals[mac] = device;
            }

            if (characteristic == null)
            {
                EmitJson("sent", new JsonObject
                {
                    ["nodeId"] = nodeId,
                    ["mac"] = mac,
                    ["success"] = false,
                    ["error"] = "No characteristic available"
                });
                return;
            }

            // Frame with [2B totalLen BE][wireBytes]
            byte[] framedCentral = new byte[2 + wireBytes.Length];
            BinaryPrimitives.WriteUInt16BigEndian(framedCentral, (ushort)wireBytes.Length);
            Array.Copy(wireBytes, 0, framedCentral, 2, wireBytes.Length);

            // Chunking into MTU-3 byte blocks (240 bytes)
            const int chunkSizeCentral = 240;
            int offsetCentral = 0;

            while (offsetCentral < framedCentral.Length)
            {
                int len = Math.Min(chunkSizeCentral, framedCentral.Length - offsetCentral);
                var writer = new DataWriter();
                var slice = new byte[len];
                Array.Copy(framedCentral, offsetCentral, slice, 0, len);
                writer.WriteBytes(slice);

                var writeRes = await characteristic.WriteValueWithResultAsync(writer.DetachBuffer(), GattWriteOption.WriteWithResponse);
                if (writeRes.Status != GattCommunicationStatus.Success)
                {
                    EmitJson("sent", new JsonObject
                    {
                        ["nodeId"] = nodeId,
                        ["mac"] = mac,
                        ["success"] = false,
                        ["error"] = $"GATT write error: {writeRes.Status}"
                    });
                    return;
                }
                offsetCentral += len;
            }

            EmitJson("sent", new JsonObject
            {
                ["nodeId"] = nodeId,
                ["mac"] = mac,
                ["success"] = true
            });
        }
        catch (Exception ex)
        {
            EmitJson("sent", new JsonObject
            {
                ["nodeId"] = nodeId,
                ["mac"] = mac,
                ["success"] = false,
                ["error"] = ex.Message
            });
        }
    }

    private static string FormatMac(ulong address)
    {
        byte[] bytes = BitConverter.GetBytes(address);
        return string.Format("{0:X2}:{1:X2}:{2:X2}:{3:X2}:{4:X2}:{5:X2}",
            bytes[5], bytes[4], bytes[3], bytes[2], bytes[1], bytes[0]);
    }

    private static ulong ParseMac(string mac)
    {
        var parts = mac.Split(':').Select(p => Convert.ToByte(p, 16)).ToArray();
        Array.Reverse(parts);
        byte[] full = new byte[8];
        Array.Copy(parts, 0, full, 0, 6);
        return BitConverter.ToUInt64(full, 0);
    }

    private static void EmitJson(string eventType, JsonObject data)
    {
        data["event"] = eventType;
        Console.WriteLine(data.ToJsonString());
    }

    private static void Cleanup()
    {
        try
        {
            _advPublisher?.Stop();
            _watcher?.Stop();
            _serviceProvider?.StopAdvertising();
            foreach (var dev in _connectedCentrals.Values)
            {
                dev.Dispose();
            }
        }
        catch { }
    }
}
