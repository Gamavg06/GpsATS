using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using AtsGpsServer.Map;
using TruckLib;
using TruckLib.HashFs;
using TruckLib.Models.Ppd;
using TruckLib.ScsMap;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class PrefabInspectionTests
    {
        private readonly ITestOutputHelper _output;

        public PrefabInspectionTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Test_NavCurve_Chaining()
        {
            string atsDir = @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
            var extractor = new MapExtractor(atsDir);
            var mapFiles = extractor.GetMapScsFiles();
            var readers = new List<IHashFsReader>();
            foreach (var f in mapFiles) { try { readers.Add(HashFsReader.Open(f)); } catch { } }

            string baseScs = Path.Combine(atsDir, "base.scs");
            using var baseReader = File.Exists(baseScs) ? HashFsReader.Open(baseScs) : null;
            string defScs = Path.Combine(atsDir, "def.scs");
            using var defReader = File.Exists(defScs) ? HashFsReader.Open(defScs) : null;

            var allFs = new List<IFileSystem>(readers);
            if (baseReader != null) allFs.Add(baseReader);
            if (defReader != null) allFs.Add(defReader);
            var compositeFs = new AssetLoader(allFs.ToArray());

            var descMap = extractor.GetPrefabDescMap(readers);
            var compositeMapFs = new AssetLoader(readers.ToArray());

            var secMap = TruckLib.ScsMap.Map.Open("/map/usa.mbd", compositeMapFs, [new SectorCoordinate(0, 2)]);
            var prefabs = secMap.MapItems.Values.OfType<Prefab>().ToList();

            int singleSegmentCount = 0;
            int multiSegmentCount = 0;

            foreach (var p in prefabs)
            {
                string modelToken = p.Model.ToString().TrimStart('/');
                if (!descMap.TryGetValue(modelToken, out string? ppdPath)) ppdPath = "/prefab/" + modelToken + ".ppd";
                if (!ppdPath.EndsWith(".ppd")) ppdPath += ".ppd";

                PrefabDescriptor? desc = null;
                try { desc = PrefabDescriptor.Open(ppdPath, compositeFs); } catch { }
                if (desc == null || desc.NavCurves.Count == 0) continue;

                for (int i = 0; i < desc.NavCurves.Count; i++)
                {
                    var nc = desc.NavCurves[i];
                    if (nc.LeadsToNodes.Item1 > 0 && nc.LeadsToNodes.Item3 > 0)
                    {
                        singleSegmentCount++;
                    }
                    else if (nc.LeadsToNodes.Item1 > 0)
                    {
                        // Sigue la cadena por NextLines
                        var visited = new HashSet<int> { i };
                        var q = new Queue<int>();
                        foreach (int next in nc.NextLines) if (next >= 0 && next < desc.NavCurves.Count) q.Enqueue(next);
                        while (q.Count > 0)
                        {
                            int curr = q.Dequeue();
                            if (visited.Contains(curr)) continue;
                            visited.Add(curr);
                            var nextNc = desc.NavCurves[curr];
                            if (nextNc.LeadsToNodes.Item3 > 0)
                            {
                                multiSegmentCount++;
                                break;
                            }
                            foreach (int next in nextNc.NextLines) if (next >= 0 && next < desc.NavCurves.Count && !visited.Contains(next)) q.Enqueue(next);
                        }
                    }
                }
            }

            _output.WriteLine($"Sector (0,2): Curvas directas (1 segmento): {singleSegmentCount}");
            _output.WriteLine($"Sector (0,2): Rutas compuestas (multi-segmento encadenadas): {multiSegmentCount}");

            foreach (var r in readers) try { r.Dispose(); } catch { }
        }
    }
}
