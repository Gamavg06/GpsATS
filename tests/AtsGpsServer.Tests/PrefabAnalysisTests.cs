using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Numerics;
using AtsGpsServer.Map;
using TruckLib;
using TruckLib.HashFs;
using TruckLib.Models.Ppd;
using TruckLib.ScsMap;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class PrefabAnalysisTests
    {
        private readonly ITestOutputHelper _output;

        public PrefabAnalysisTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Verify_Prefab_Origin_Transform_Formula()
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

            _output.WriteLine($"Prefabs en sector (0,2): {prefabs.Count}");

            int testedCount = 0;
            foreach (var p in prefabs)
            {
                string modelToken = p.Model.ToString().TrimStart('/');
                if (!descMap.TryGetValue(modelToken, out string? ppdPath)) ppdPath = "/prefab/" + modelToken + ".ppd";
                if (!ppdPath.EndsWith(".ppd")) ppdPath += ".ppd";

                PrefabDescriptor? desc = null;
                try { desc = PrefabDescriptor.Open(ppdPath, compositeFs); } catch { }
                if (desc == null || desc.NavCurves.Count == 0) continue;

                int origIdx = (int)p.Origin;
                if (origIdx < desc.Nodes.Count && origIdx < p.Nodes.Count)
                {
                    var ppdOrig = desc.Nodes[origIdx].Position;
                    var worldOrigNode = p.Nodes[origIdx];

                    _output.WriteLine($"Prefab UID: {p.Uid}, Model: {modelToken}, Origin Index: {origIdx}");
                    _output.WriteLine($"  PPD Origin Node Pos: ({ppdOrig.X:F2}, {ppdOrig.Y:F2}, {ppdOrig.Z:F2})");
                    _output.WriteLine($"  World Origin Node Pos: ({worldOrigNode.Position.X:F2}, {worldOrigNode.Position.Y:F2}, {worldOrigNode.Position.Z:F2})");

                    for (int i = 0; i < p.Nodes.Count && i < desc.Nodes.Count; i++)
                    {
                        var ppdNodePos = desc.Nodes[i].Position;
                        var worldActual = p.Nodes[i].Position;

                        // Fórmula antigua errónea
                        var worldOld = Vector3.Transform(ppdNodePos, worldOrigNode.Rotation) + new Vector3(worldOrigNode.Position.X, worldOrigNode.Position.Y, worldOrigNode.Position.Z);
                        float errOld = Vector3.Distance(worldOld, new Vector3(worldActual.X, worldActual.Y, worldActual.Z));

                        // Fórmula nueva con resta del offset del nodo origen en el PPD
                        var worldNew = Vector3.Transform(ppdNodePos - ppdOrig, worldOrigNode.Rotation) + new Vector3(worldOrigNode.Position.X, worldOrigNode.Position.Y, worldOrigNode.Position.Z);
                        float errNew = Vector3.Distance(worldNew, new Vector3(worldActual.X, worldActual.Y, worldActual.Z));

                        _output.WriteLine($"    Node {i}: Actual=({worldActual.X:F1},{worldActual.Z:F1}) | ErrAntiguo={errOld:F3}m | ErrNuevo={errNew:F4}m");
                    }

                    testedCount++;
                    if (testedCount >= 3) break;
                }
            }

            foreach (var r in readers) try { r.Dispose(); } catch { }
        }
    }
}
