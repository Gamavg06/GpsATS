using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using TruckLib.HashFs;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class DiagnosticExtractorInspectionTests
    {
        private readonly ITestOutputHelper _output;

        public DiagnosticExtractorInspectionTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Inspect_Prefab_Sii_Files_Across_All_Dlc()
        {
            string atsDir = @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
            Assert.True(Directory.Exists(atsDir), $"ATS dir no existe: {atsDir}");

            var scsFiles = Directory.GetFiles(atsDir, "*.scs")
                .Where(f => {
                    string name = Path.GetFileName(f).ToLowerInvariant();
                    return name == "def.scs" || name == "base.scs" || name.StartsWith("base_map") || name.StartsWith("dlc_");
                })
                .OrderBy(f => f)
                .ToList();

            _output.WriteLine($"Archivos SCS encontrados: {scsFiles.Count}");

            var allPrefabSiiFiles = new Dictionary<string, List<string>>(); // dlcName -> list of prefab*.sii
            var totalDefinitions = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

            var regex = new System.Text.RegularExpressions.Regex(
                @"prefab_model\s*:\s*prefab\.([a-zA-Z0-9_]+)\s*\{[^}]*prefab_desc\s*:\s*""([^""]+)""",
                System.Text.RegularExpressions.RegexOptions.Singleline);

            foreach (var file in scsFiles)
            {
                string pkgName = Path.GetFileName(file);
                try
                {
                    using var reader = HashFsReader.Open(file);
                    if (reader.DirectoryExists("/def/world"))
                    {
                        var entries = reader.GetFiles("/def/world");
                        var prefabFiles = entries.Where(e => Path.GetFileName(e).StartsWith("prefab", StringComparison.OrdinalIgnoreCase) && e.EndsWith(".sii", StringComparison.OrdinalIgnoreCase)).ToList();
                        if (prefabFiles.Count > 0)
                        {
                            allPrefabSiiFiles[pkgName] = prefabFiles;
                            foreach (var pfile in prefabFiles)
                            {
                                try
                                {
                                    var bytes = reader.Extract(pfile)[0];
                                    string text = System.Text.Encoding.UTF8.GetString(bytes);
                                    foreach (System.Text.RegularExpressions.Match m in regex.Matches(text))
                                    {
                                        totalDefinitions[m.Groups[1].Value] = m.Groups[2].Value;
                                    }
                                }
                                catch { }
                            }
                        }
                    }
                }
                catch { }
            }

            _output.WriteLine("\n=== ARCHIVOS DE DEFINICIÓN DE PREFABS (prefab*.sii) POR PAQUETE ===");
            foreach (var kvp in allPrefabSiiFiles)
            {
                _output.WriteLine($"Paquete: {kvp.Key} ({kvp.Value.Count} archivos):");
                foreach (var f in kvp.Value)
                {
                    _output.WriteLine($"   - {f}");
                }
            }

            _output.WriteLine($"\nTOTAL DE DEFINICIONES DE PREFAB EXTRAÍDAS: {totalDefinitions.Count}");
        }
    }
}
