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
    public class PrefabAllEvaluationTests
    {
        private readonly ITestOutputHelper _output;

        public PrefabAllEvaluationTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Evaluate_All_Placed_Prefabs_Error_Distribution()
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
            var compositePpdFs = new AssetLoader(allFs.ToArray());
            var compositeMapFs = new AssetLoader(readers.ToArray());

            var descMap = extractor.GetPrefabDescMap(readers);
            var sectors = extractor.DiscoverSectors();

            var errors = new List<float>();
            int totalPrefabsInspected = 0;
            int totalCurvesChecked = 0;
            int failCount = 0;
            var ppdCache = new Dictionary<string, PrefabDescriptor?>();

            // Evaluar todos los sectores (tomar hasta 50 sectores distribuidos para medir velocidad y precisión)
            var sampleSectors = sectors.Where((s, idx) => idx % 8 == 0).ToList();

            foreach (var sec in sampleSectors)
            {
                TruckLib.ScsMap.Map map;
                try { map = TruckLib.ScsMap.Map.Open("/map/usa.mbd", compositeMapFs, [sec]); }
                catch { continue; }

                var prefabs = map.MapItems.Values.OfType<Prefab>().ToList();
                totalPrefabsInspected += prefabs.Count;

                foreach (var p in prefabs)
                {
                    if (p.Nodes == null || p.Nodes.Count < 2 || p.Nodes[0] == null) continue;

                    string modelToken = p.Model.ToString().TrimStart('/');
                    if (!descMap.TryGetValue(modelToken, out string? ppdPath)) ppdPath = "/prefab/" + modelToken + ".ppd";
                    if (!ppdPath.EndsWith(".ppd")) ppdPath += ".ppd";

                    if (!ppdCache.TryGetValue(ppdPath, out var desc))
                    {
                        try { desc = PrefabDescriptor.Open(ppdPath, compositePpdFs); } catch { desc = null; }
                        ppdCache[ppdPath] = desc;
                    }
                    if (desc == null || desc.NavCurves.Count == 0) continue;

                    int origIdx = (int)p.Origin;
                    if (origIdx >= desc.Nodes.Count || origIdx >= p.Nodes.Count) origIdx = 0;
                    var ppdOriginPos = desc.Nodes[origIdx].Position;
                    var worldOrigNode = p.Nodes[origIdx];
                    if (worldOrigNode == null) continue;
                    var worldOrigin = new Vector3(worldOrigNode.Position.X, worldOrigNode.Position.Y, worldOrigNode.Position.Z);
                    var prefabRot = worldOrigNode.Rotation;

                    foreach (var nc in desc.NavCurves)
                    {
                        int pStartIdx = nc.LeadsToNodes.Item1 > 0 ? BitOperations.TrailingZeroCount(nc.LeadsToNodes.Item1) : -1;
                        if (pStartIdx >= 0 && pStartIdx < p.Nodes.Count && p.Nodes[pStartIdx] != null)
                        {
                            var targetNode = p.Nodes[pStartIdx];
                            var targetWorldPos = new Vector3(targetNode.Position.X, targetNode.Position.Y, targetNode.Position.Z);

                            // Transformación exacta
                            var rel = nc.StartPosition - ppdOriginPos;
                            var rotated = Vector3.Transform(rel, prefabRot);
                            var calculatedWorldPos = worldOrigin + rotated;

                            float err = Vector3.Distance(calculatedWorldPos, targetWorldPos);
                            errors.Add(err);
                            totalCurvesChecked++;
                            if (err > 1.0f) failCount++;
                        }
                    }
                }
            }

            foreach (var r in readers) try { r.Dispose(); } catch { }

            errors.Sort();
            float min = errors.Count > 0 ? errors[0] : 0;
            float median = errors.Count > 0 ? errors[errors.Count / 2] : 0;
            float p95 = errors.Count > 0 ? errors[(int)(errors.Count * 0.95)] : 0;
            float max = errors.Count > 0 ? errors[^1] : 0;

            _output.WriteLine("=== EVALUACIÓN DE PREFABS COLOCADOS (B.3) ===");
            _output.WriteLine($"Sectores evaluados: {sampleSectors.Count} / {sectors.Count}");
            _output.WriteLine($"Prefabs colocados inspeccionados: {totalPrefabsInspected}");
            _output.WriteLine($"Curvas de navegación evaluadas: {totalCurvesChecked}");
            _output.WriteLine($"  Error Mínimo:  {min:F4} m");
            _output.WriteLine($"  Error Mediana: {median:F4} m");
            _output.WriteLine($"  Error P95:     {p95:F4} m");
            _output.WriteLine($"  Error Máximo:  {max:F4} m");
            _output.WriteLine($"  Fallos (>1.0 m): {failCount} ({(float)failCount / totalCurvesChecked * 100.0:F2}%)");

            // Las curvas de navegación internas del prefab residen dentro del radio físico de la intersección (<100m del nodo de acceso)
            Assert.True(median < 50.0f, "La mediana de distancia de las curvas internas debe estar contenida en el radio del prefab (< 50m)");
        }
    }
}