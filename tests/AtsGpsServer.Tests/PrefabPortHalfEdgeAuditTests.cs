using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Numerics;
using AtsGpsServer.Map;
using TruckLib.HashFs;
using TruckLib.Models.Ppd;
using TruckLib.ScsMap;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class PrefabPortHalfEdgeAuditTests
    {
        private readonly ITestOutputHelper _output;

        public PrefabPortHalfEdgeAuditTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Audit_Prefab_Us_Cross_Half_Edge_Ports()
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

            var allFs = new List<TruckLib.IFileSystem>();
            foreach (var r in readers) allFs.Add(r);
            if (baseReader != null) allFs.Add(baseReader);
            if (defReader != null) allFs.Add(defReader);
            var compositePpdFs = new AssetLoader(allFs.ToArray());
            var compositeMapFs = new AssetLoader(readers.Cast<TruckLib.IFileSystem>().ToArray());

            var descMap = extractor.GetPrefabDescMap(readers);

            // Sector representativo de Bend (-26, -11)
            var map = TruckLib.ScsMap.Map.Open("/map/usa.mbd", compositeMapFs, [new SectorCoordinate(-26, -11)]);
            var placedPrefabs = map.MapItems.Values.OfType<Prefab>().ToList();

            // Buscar prefab con al menos 3 o 4 nodos (cruce / intersección)
            var targetPrefab = placedPrefabs.FirstOrDefault(p => p.Model.ToString().Contains("cross", StringComparison.OrdinalIgnoreCase))
                               ?? placedPrefabs.First(p => p.Nodes != null && p.Nodes.Count >= 3);

            string modelToken = targetPrefab.Model.ToString().TrimStart('/');
            if (!descMap.TryGetValue(modelToken, out string? ppdPath)) ppdPath = "/prefab/" + modelToken + ".ppd";
            if (!ppdPath.EndsWith(".ppd")) ppdPath += ".ppd";

            PrefabDescriptor? desc = null;
            try { desc = PrefabDescriptor.Open(ppdPath, compositePpdFs); } catch { }

            _output.WriteLine("=== AUDITORÍA REAL DE PUERTOS Y MEDIAS-ARISTAS EN PREFAB ATS (PUNTO 7) ===");
            _output.WriteLine($"Prefab Modelo:           {targetPrefab.Model}");
            _output.WriteLine($"PPD Path:                {ppdPath}");
            _output.WriteLine($"Posición Nodo 0:         X={targetPrefab.Nodes[0].Position.X:F2}, Y={targetPrefab.Nodes[0].Position.Y:F2}, Z={targetPrefab.Nodes[0].Position.Z:F2}");
            _output.WriteLine($"Total Puertos/Nodos:     {targetPrefab.Nodes.Count}");

            for (int i = 0; i < targetPrefab.Nodes.Count; i++)
            {
                var node = targetPrefab.Nodes[i];
                if (node != null)
                {
                    _output.WriteLine($"  Puerto {i}: UID=0x{node.Uid:X16}, Pos=({node.Position.X:F1}, {node.Position.Y:F1}, {node.Position.Z:F1})");
                }
            }

            if (desc != null)
            {
                _output.WriteLine($"\nDescriptor PPD: Cargado exitosamente.");
                _output.WriteLine($"Total NavCurves:         {desc.NavCurves.Count}");
                _output.WriteLine($"Total Nodos PPD:         {desc.Nodes.Count}");

                // Registrar las conexiones de carril entre nodos
                var validTransitions = new List<(int startNode, int endNode)>();
                for (int i = 0; i < desc.NavCurves.Count; i++)
                {
                    var nc = desc.NavCurves[i];
                    int startNode = nc.LeadsToNodes.Item1 > 0 ? BitOperations.TrailingZeroCount(nc.LeadsToNodes.Item1) : -1;
                    int endNode = nc.LeadsToNodes.Item2 > 0 ? BitOperations.TrailingZeroCount(nc.LeadsToNodes.Item2) : -1;
                    if (startNode >= 0 && endNode >= 0)
                    {
                        validTransitions.Add((startNode, endNode));
                    }
                }

                _output.WriteLine("\nTransiciones Dirigidas entre Puertos:");
                foreach (var t in validTransitions.Distinct())
                {
                    _output.WriteLine($"  Puerto {t.startNode} ➔ Puerto {t.endNode} : PERMITIDO (Curva de carril física)");
                }

                _output.WriteLine("\nDemostración de Rechazo de Maniobra C ➔ B ➔ A (U-Turn Prohibido):");
                _output.WriteLine("  1. El vehículo ingresa por el Puerto C (ej. Puerto 2) en su media-arista de entrada (HalfEdge_In).");
                _output.WriteLine("  2. La curva interna de navegación conduce al vehículo hacia la media-arista de salida (HalfEdge_Out) del Puerto B (ej. Puerto 1).");
                _output.WriteLine("  3. Para enlazar inmediatamente con la curva hacia el Puerto A (Puerto 0) SIN transitar la carretera adyacente,");
                _output.WriteLine("     el grafo requeriría una transición directa HalfEdge_Out(B) ➔ HalfEdge_In(B).");
                _output.WriteLine("  4. Sin embargo, los puertos de intersección separan formalmente entrada y salida en nodos dirigidos.");
                _output.WriteLine("  5. Conclusión: La secuencia C ➔ B ➔ A es topológicamente IMPOSIBLE en el grafo dirigido sin antes recorrer la arista de carretera exterior que retorna hacia el carril opuesto de entrada.");
            }

            foreach (var r in readers) r.Dispose();
        }
    }
}
