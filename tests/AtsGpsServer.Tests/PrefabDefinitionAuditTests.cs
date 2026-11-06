using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using TruckLib.HashFs;
using TruckLib.ScsMap;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class PrefabDefinitionAuditTests
    {
        private readonly ITestOutputHelper _output;

        public PrefabDefinitionAuditTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Audit_Placed_Prefabs_Missing_Descriptors_Before_And_After()
        {
            string atsDir = @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
            var files = Directory.GetFiles(atsDir, "*.scs")
                .Where(f => {
                    string n = Path.GetFileName(f).ToLowerInvariant();
                    return n == "base.scs" || n == "def.scs" || n.StartsWith("base_map") || n.StartsWith("dlc_");
                })
                .ToList();

            var readers = new List<IHashFsReader>();
            foreach (var f in files)
            {
                try { readers.Add(HashFsReader.Open(f)); } catch { }
            }

            var assetLoader = new AssetLoader(readers.ToArray());

            // 1. Cargar definiciones usando solo /def/world/prefab.sii (VIEJO)
            var oldMap = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            var regex = new System.Text.RegularExpressions.Regex(
                @"prefab_model\s*:\s*prefab\.([a-zA-Z0-9_]+)\s*\{[^}]*prefab_desc\s*:\s*""([^""]+)""",
                System.Text.RegularExpressions.RegexOptions.Singleline);

            foreach (var r in readers)
            {
                try
                {
                    if (r.EntryExists("/def/world/prefab.sii") != EntryType.NotFound)
                    {
                        var bytes = r.Extract("/def/world/prefab.sii")[0];
                        string text = System.Text.Encoding.UTF8.GetString(bytes);
                        foreach (System.Text.RegularExpressions.Match m in regex.Matches(text))
                        {
                            oldMap[m.Groups[1].Value] = m.Groups[2].Value;
                        }
                    }
                }
                catch { }
            }

            // 2. Cargar definiciones usando TODOS los prefab*.sii en todos los paquetes (NUEVO)
            var newMap = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var r in readers)
            {
                try
                {
                    if (r.DirectoryExists("/def/world"))
                    {
                        foreach (var entry in r.GetFiles("/def/world"))
                        {
                            if (Path.GetFileName(entry).StartsWith("prefab", StringComparison.OrdinalIgnoreCase) && entry.EndsWith(".sii", StringComparison.OrdinalIgnoreCase))
                            {
                                try
                                {
                                    var bytes = r.Extract(entry)[0];
                                    string text = System.Text.Encoding.UTF8.GetString(bytes);
                                    foreach (System.Text.RegularExpressions.Match m in regex.Matches(text))
                                    {
                                        newMap[m.Groups[1].Value] = m.Groups[2].Value;
                                    }
                                }
                                catch { }
                            }
                        }
                    }
                }
                catch { }
            }

            _output.WriteLine($"Definiciones con método VIEJO (/def/world/prefab.sii): {oldMap.Count}");
            _output.WriteLine($"Definiciones con método NUEVO (todos los prefab*.sii): {newMap.Count}");

            // 3. Evaluar en una muestra amplia de sectores en Oregón y Washington
            var testSectors = new[]
            {
                new SectorCoordinate(-26, -11), // Bend
                new SectorCoordinate(-26, -14), // Portland
                new SectorCoordinate(-25, -16), // Seattle
                new SectorCoordinate(-24, -13), // Pendleton
                new SectorCoordinate(-27, -12), // Eugene
            };

            int placedTotal = 0;
            int missingOld = 0;
            int missingNew = 0;

            foreach (var sec in testSectors)
            {
                try
                {
                    var map = TruckLib.ScsMap.Map.Open("/map/usa.mbd", assetLoader, new[] { sec });
                    var prefabs = map.MapItems.Values.OfType<Prefab>().ToList();
                    placedTotal += prefabs.Count;

                    foreach (var p in prefabs)
                    {
                        string token = p.Model.ToString().TrimStart('/');
                        if (!oldMap.ContainsKey(token)) missingOld++;
                        if (!newMap.ContainsKey(token)) missingNew++;
                    }
                }
                catch { }
            }

            _output.WriteLine($"\nEn los sectores analizados (Bend, Portland, Seattle, Pendleton, Eugene):");
            _output.WriteLine($"  Prefabs colocados: {placedTotal}");
            _output.WriteLine($"  Prefabs SIN descriptor con método VIEJO: {missingOld} ({(missingOld * 100.0 / placedTotal):F1}%)");
            _output.WriteLine($"  Prefabs SIN descriptor con método NUEVO: {missingNew} ({(missingNew * 100.0 / placedTotal):F1}%)");

            foreach (var r in readers) r.Dispose();
        }
    }
}
