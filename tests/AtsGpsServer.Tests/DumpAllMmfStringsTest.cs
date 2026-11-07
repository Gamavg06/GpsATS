using System;
using System.IO.MemoryMappedFiles;
using System.Text;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class DumpAllMmfStringsTest
    {
        private readonly ITestOutputHelper _output;

        public DumpAllMmfStringsTest(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Dump_All_Strings()
        {
            using var mmf = MemoryMappedFile.OpenExisting(@"Local\SCSTelemetry", MemoryMappedFileRights.Read);
            using var accessor = mmf.CreateViewAccessor(0, 32 * 1024, MemoryMappedFileAccess.Read);

            byte[] allBytes = new byte[32 * 1024];
            accessor.ReadArray(0, allBytes, 0, allBytes.Length);

            _output.WriteLine("=== STRINGS FOUND IN SHARED MEMORY ===");
            for (int i = 0; i < allBytes.Length; i++)
            {
                if (allBytes[i] >= 32 && allBytes[i] <= 126)
                {
                    int start = i;
                    while (i < allBytes.Length && allBytes[i] >= 32 && allBytes[i] <= 126)
                    {
                        i++;
                    }
                    int len = i - start;
                    if (len >= 3)
                    {
                        string s = Encoding.UTF8.GetString(allBytes, start, len);
                        _output.WriteLine($"[{start,5} - 0x{start:X4}] len={len,2}: \"{s}\"");
                    }
                }
            }

            // Also check Zone 10: unsigned long long jobIncome
            _output.WriteLine("\n=== UINT64 >= 0 in Zone 10 (4000..4200) ===");
            for (int offset = 4000; offset < 4200; offset += 8)
            {
                ulong v = accessor.ReadUInt64(offset);
                if (v != 0)
                {
                    _output.WriteLine($"Offset {offset}: {v} (0x{v:X})");
                }
            }

            // Check Zone 2: config_ui (time_abs_delivery, etc.)
            // config_ui starts at offset:
            // 40 + 24 (scs_values) + 4 (common_ui: time_abs) = 68
            // 68: gears (4)
            // 72: gears_reverse (4)
            // 76: retarderStepCount (4)
            // 80: truckWheelCount (4)
            // 84: selectorCount (4)
            // 88: time_abs_delivery (4)
            // 92: maxTrailerCount (4)
            // 96: unitCount (4)
            // 100: plannedDistanceKm (4)
            _output.WriteLine("\n=== ZONE 2 (INTEGERS) ===");
            _output.WriteLine($"time_abs (offset 64): {accessor.ReadUInt32(64)}");
            _output.WriteLine($"time_abs_delivery (offset 88): {accessor.ReadUInt32(88)}");
            _output.WriteLine($"plannedDistanceKm (offset 100): {accessor.ReadUInt32(100)}");

            // Check job_f cargoDamage
            // Zone 4 floats start at 700
            // config_f: 704..
            // truck_f: 948..
            // Let's print float at 1060 (navDist), 1064 (navTime), 1068 (speedLimit)
            _output.WriteLine("\n=== NAVIGATION FLOATS ===");
            _output.WriteLine($"navDistance (1060): {accessor.ReadSingle(1060)}");
            _output.WriteLine($"navTime (1064): {accessor.ReadSingle(1064)}");
            _output.WriteLine($"speedLimit (1068): {accessor.ReadSingle(1068)}");

            // Trailer 0: offset 6000
            // scsTrailer_t com_f at offset 6000 + 152 = 6152
            // cargoDamage (6152), wearChassis (6156), wearWheels (6160), wearBody (6164)
            _output.WriteLine("\n=== TRAILER 0 FLOATS ===");
            _output.WriteLine($"trailer cargoDamage (6152): {accessor.ReadSingle(6152)}");
            _output.WriteLine($"trailer wearChassis (6156): {accessor.ReadSingle(6156)}");
            _output.WriteLine($"trailer wearWheels (6158): {accessor.ReadSingle(6160)}");
            _output.WriteLine($"trailer wearBody (6164): {accessor.ReadSingle(6164)}");
        }
    }
}
