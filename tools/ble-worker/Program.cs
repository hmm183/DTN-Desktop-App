using System;
using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
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
    private static GattServiceProvider? _serviceProvider;
    private static GattLocalCharacteristic? _bundleCharacteristic;
    private static GattLocalCharacteristic? _linkCharacteristic;

    // Buffer for reassembling incoming chunks per client MAC/Session
    private class RxBuffer
    {
        public int TotalLength;
        public MemoryStream Stream = new();
    }
    private static readonly ConcurrentDictionary<string, RxBuffer> _rxBuffers = new();
    private static readonly ConcurrentDictionary<string, BluetoothLEDevice> _connectedCentrals = new();

    public static async Task Main(string[] args)
    {
        Console.OutputEncoding = Encoding.UTF8;
        Console.InputEncoding = Encoding.UTF8;

        int localNodeNum = 0;
        string localNodeId = "";

        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] == "--node-num" && i + 1 < args.Length)
            {
                int.TryParse(args[i + 1], out localNodeNum);
            }
            else if (args[i] == "--node-id" && i + 1 < args.Length)
            {
                localNodeId = args[i + 1];
            }
        }

        try
        {
            await InitGattServerAsync();
            InitScanner();
            EmitJson("status", new JsonObject
            {
                ["available"] = true,
                ["message"] = "BLE GATT Server and Scanner active"
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
                        _ = SendBundleToPeerAsync(mac, nodeId, wireBase64);
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

        // 1. Bundle characteristic
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

        // 2. Link characteristic
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

            string sessionKey = "default";

            if (data.Length >= 2)
            {
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
                            ["rssi"] = -55
                        });
                    }
                }
            }

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

                // Check DataSections for ServiceData (0x16 or 0x21)
                foreach (var section in e.Advertisement.DataSections)
                {
                    if (section.DataType == 0x16 || section.DataType == 0x21)
                    {
                        var reader = DataReader.FromBuffer(section.Data);
                        byte[] bytes = new byte[reader.UnconsumedBufferLength];
                        reader.ReadBytes(bytes);

                        // If 16-bit UUID or 128-bit UUID matches, extract 4-byte nodeNum
                        if (bytes.Length >= 4)
                        {
                            nodeNum = BinaryPrimitives.ReadInt32BigEndian(bytes);
                            hasService = true;
                            break;
                        }
                    }
                }

                if (hasService)
                {
                    ulong addr = e.BluetoothAddress;
                    string mac = FormatMac(addr);
                    string nodeId = nodeNum.HasValue ? "!" + ((uint)nodeNum.Value).ToString("x8") : "!ble_" + mac.Replace(":", "").ToLower();

                    EmitJson("discovered", new JsonObject
                    {
                        ["nodeId"] = nodeId,
                        ["mac"] = mac,
                        ["rssi"] = e.RawSignalStrengthInDBm
                    });
                }
            }
            catch { }
        };

        _watcher.Start();
    }

    private static async Task SendBundleToPeerAsync(string mac, string nodeId, string wireBase64)
    {
        try
        {
            byte[] wireBytes = Convert.FromBase64String(wireBase64);
            ulong address = ParseMac(mac);

            using var device = await BluetoothLEDevice.FromBluetoothAddressAsync(address);
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

            var characteristic = charResult.Characteristics[0];

            // Frame with [2B totalLen BE][wireBytes]
            byte[] framed = new byte[2 + wireBytes.Length];
            BinaryPrimitives.WriteUInt16BigEndian(framed, (ushort)wireBytes.Length);
            Array.Copy(wireBytes, 0, framed, 2, wireBytes.Length);

            // Chunking into MTU-3 byte blocks (typically 240 bytes)
            const int chunkSize = 240;
            int offset = 0;

            while (offset < framed.Length)
            {
                int len = Math.Min(chunkSize, framed.Length - offset);
                var writer = new DataWriter();
                var slice = new byte[len];
                Array.Copy(framed, offset, slice, 0, len);
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
                offset += len;
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
            _watcher?.Stop();
            _serviceProvider?.StopAdvertising();
        }
        catch { }
    }
}
