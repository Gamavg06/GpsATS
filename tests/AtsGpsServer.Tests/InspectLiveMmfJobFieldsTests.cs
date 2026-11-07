using System;
using System.IO.MemoryMappedFiles;
using System.Text;
using AtsGpsServer.Telemetry;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class InspectLiveMmfJobFieldsTests
    {
        private readonly ITestOutputHelper _output;

        public InspectLiveMmfJobFieldsTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Dump_All_Strings_And_Job_Fields_In_Shared_Memory()
        {
            using var mmf = MemoryMappedFile.OpenExisting(@"Local\SCSTelemetry", MemoryMappedFileRights.Read);
            using var accessor = mmf.CreateViewAccessor(0, 32 * 1024, MemoryMappedFileAccess.Read);

            bool sdkActive = accessor.ReadBoolean(0);
            bool paused = accessor.ReadBoolean(4);
            long time = accessor.ReadInt64(8);

            _output.WriteLine($"Offset 0 (sdkActive): {sdkActive}");
            _output.WriteLine($"Offset 4 (paused): {paused}");
            _output.WriteLine($"Offset 8 (time): {time}");

            // Search for strings like "San Francisco", "Redding", "Customer", "Envío", 1438, etc.
            byte[] allBytes = new byte[32 * 1024];
            accessor.ReadArray(0, allBytes, 0, allBytes.Length);

            _output.WriteLine("\n=== BUSQUEDA DE CADENAS DE TEXTO RELEVANTES ===");
            for (int i = 0; i < allBytes.Length - 4; i++)
            {
                // Comprobar si hay texto ASCII/UTF-8 imprimible de al menos 4 caracteres
                if (allBytes[i] >= 32 && allBytes[i] <= 126)
                {
                    int end = i;
                    while (end < allBytes.Length && allBytes[end] >= 32 && allBytes[end] <= 126)
                    {
                        end++;
                    }
                    if (end - i >= 4)
                    {
                        string str = Encoding.UTF8.GetString(allBytes, i, end - i);
                        if (str.Contains("San", StringComparison.OrdinalIgnoreCase) ||
                            str.Contains("Redding", StringComparison.OrdinalIgnoreCase) ||
                            str.Contains("Francis", StringComparison.OrdinalIgnoreCase) ||
                            str.Contains("cargo", StringComparison.OrdinalIgnoreCase) ||
                            str.Contains("job", StringComparison.OrdinalIgnoreCase) ||
                            str.Contains("truck", StringComparison.OrdinalIgnoreCase) ||
                            str.Contains("darchelle", StringComparison.OrdinalIgnoreCase) ||
                            str.Contains("sell", StringComparison.OrdinalIgnoreCase) ||
                            str.Contains("oakland", StringComparison.OrdinalIgnoreCase))
                        {
                            _output.WriteLine($"Offset {i} (0x{i:X4}): \"{str}\"");
                        }
                    }
                    i = end;
                }
            }

            _output.WriteLine("\n=== INSPECCION DE ZONA 9 (STRINGS TEÓRICAS) ===");
            // Probar offsets de 2200 a 3500 cada 32/64 bytes
            for (int offset = 2200; offset <= 3600; offset += 32)
            {
                string s = ReadStr(accessor, offset, 64);
                if (!string.IsNullOrWhiteSpace(s))
                {
                    _output.WriteLine($"Offset {offset}: \"{s}\"");
                }
            }

            _output.WriteLine("\n=== INSPECCION DE JOB INCOME (ZONA 10) ===");
            for (int offset = 3980; offset <= 4220; offset += 8)
            {
                ulong val = accessor.ReadUInt64(offset);
                if (val > 0 && val < 10000000)
                {
                    _output.WriteLine($"Offset {offset} (UInt64): {val}");
                }
            }

            _output.WriteLine("\n=== INSPECCION DE BOOLEANOS DE CARGA (ZONA 5) ===");
            for (int offset = 1500; offset <= 1650; offset++)
            {
                bool b = accessor.ReadBoolean(offset);
                if (b)
                {
                    _output.WriteLine($"Offset {offset} (Boolean): TRUE");
                }
            }

            _output.WriteLine("\n=== INSPECCION DE NAVEGACION Y TRABAJO (FLOTANTES) ===");
            for (int offset = 1050; offset <= 1100; offset += 4)
            {
                float f = accessor.ReadSingle(offset);
                _output.WriteLine($"Offset {offset} (Single): {f}");
            }
        }

        private static string ReadStr(MemoryMappedViewAccessor accessor, long offset, int maxLen)
        {
            byte[] buf = new byte[maxLen];
            accessor.ReadArray(offset, buf, 0, maxLen);
            int len = 0;
            while (len < maxLen && buf[len] != 0 && buf[len] >= 32 && buf[len] <= 126) len++;
            return len > 0 ? Encoding.UTF8.GetString(buf, 0, len) : string.Empty;
        }
    }
}
