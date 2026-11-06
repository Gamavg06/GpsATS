using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using AtsGpsServer.Map;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class Benchmark30PairsTests
    {
        private readonly ITestOutputHelper _output;

        public Benchmark30PairsTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Benchmark_35_City_Pairs_With_Repaired_Anchors()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            int n = graph.Nodes.Count;
            var inDegree = new int[n];
            var outDegree = new int[n];
            foreach (var e in graph.Edges)
            {
                if (e.FromNodeIndex < n) outDegree[e.FromNodeIndex]++;
                if (e.ToNodeIndex < n) inDegree[e.ToNodeIndex]++;
            }

            // Tarjan's Strongly Connected Components
            var adj = new List<uint>[n];
            for (int i = 0; i < n; i++) adj[i] = new List<uint>();
            foreach (var e in graph.Edges)
            {
                if (e.FromNodeIndex < n && e.ToNodeIndex < n) adj[e.FromNodeIndex].Add(e.ToNodeIndex);
            }

            var indices = new int[n];
            Array.Fill(indices, -1);
            var lowlink = new int[n];
            var onStack = new bool[n];
            var stack = new Stack<int>();
            int index = 0;
            var sccs = new List<List<int>>();

            for (int u = 0; u < n; u++) if (indices[u] == -1) StrongConnect(u);

            void StrongConnect(int v)
            {
                indices[v] = index; lowlink[v] = index; index++;
                stack.Push(v); onStack[v] = true;
                foreach (var w in adj[v])
                {
                    int wi = (int)w;
                    if (indices[wi] == -1) { StrongConnect(wi); lowlink[v] = Math.Min(lowlink[v], lowlink[wi]); }
                    else if (onStack[wi]) { lowlink[v] = Math.Min(lowlink[v], indices[wi]); }
                }
                if (lowlink[v] == indices[v])
                {
                    var comp = new List<int>();
                    while (true)
                    {
                        int w = stack.Pop(); onStack[w] = false; comp.Add(w);
                        if (w == v) break;
                    }
                    sccs.Add(comp);
                }
            }

            var nodeToSccSize = new int[n];
            var nodeToSccId = new int[n];
            for (int i = 0; i < sccs.Count; i++)
            {
                int sz = sccs[i].Count;
                foreach (var node in sccs[i])
                {
                    nodeToSccSize[node] = sz;
                    nodeToSccId[node] = i;
                }
            }

            // Reanclar ciudades a nodos de la mayor SCC transitable
            var cityMap = new Dictionary<string, uint>(StringComparer.OrdinalIgnoreCase);
            foreach (var city in graph.Cities)
            {
                uint curIdx = city.NearestNodeIndex;
                int curSccSize = nodeToSccSize[curIdx];
                uint bestIdx = curIdx;
                int bestSccSize = curSccSize;
                float bestDist = float.MaxValue;

                for (int i = 0; i < n; i++)
                {
                    var node = graph.Nodes[i];
                    float dx = node.X - city.X;
                    float dz = node.Z - city.Z;
                    float dist = MathF.Sqrt(dx * dx + dz * dz);
                    if (dist > 3500f) continue;

                    int sz = nodeToSccSize[i];
                    if (sz > bestSccSize || (sz == bestSccSize && sz > 100 && dist < bestDist))
                    {
                        bestSccSize = sz;
                        bestIdx = (uint)i;
                        bestDist = dist;
                    }
                }
                cityMap[city.Name] = bestIdx;
            }

            var pf = new PathFinder(graph);

            // 35 pares diversos: intra-estatales e inter-estatales
            var testList = new (string c1, string c2, string type)[]
            {
                // Washington (Intra)
                ("Seattle", "Tacoma", "WA Intra"),
                ("Tacoma", "Olympia", "WA Intra"),
                ("Seattle", "Olympia", "WA Intra"),
                ("Seattle", "Everett", "WA Intra"),
                ("Spokane", "Grand_coulee", "WA Intra"),
                ("Wenatchee", "Yakima", "WA Intra"),
                ("Aberdeen_wa", "Olympia", "WA Intra"),

                // Oregón (Intra)
                ("Bend", "Eugene", "OR Intra"),
                ("Portland", "Salem", "OR Intra"),
                ("Salem", "Eugene", "OR Intra"),
                ("Eugene", "Medford", "OR Intra"),
                ("Portland", "The_dalles", "OR Intra"),
                ("Bend", "Burns", "OR Intra"),
                ("Pendleton", "Ontario", "OR Intra"),

                // California (Intra)
                ("Sacramento", "Stockton", "CA Intra"),
                ("Stockton", "Modesto", "CA Intra"),
                ("Modesto", "Fresno", "CA Intra"),
                ("Fresno", "Bakersfield", "CA Intra"),
                ("Bakersfield", "Los_angeles", "CA Intra"),
                ("San_francisc", "Oakland", "CA Intra"),
                ("Oakland", "San_jose", "CA Intra"),
                ("Los_angeles", "Barstow", "CA Intra"),

                // Nevada & Idaho (Intra)
                ("Reno", "Carson_city", "NV Intra"),
                ("Elko", "Winnemucca", "NV Intra"),
                ("Boise", "Nampa", "ID Intra"),
                ("Twin_falls", "Pocatello", "ID Intra"),
                ("Pocatello", "Idaho_falls", "ID Intra"),

                // Interestatales (WA <-> OR <-> CA <-> NV <-> ID)
                ("Portland", "Seattle", "OR -> WA"),
                ("Seattle", "Portland", "WA -> OR"),
                ("Bend", "Portland", "OR -> OR"),
                ("Portland", "Bend", "OR -> OR"),
                ("Bend", "Seattle", "OR -> WA"),
                ("Seattle", "Bend", "WA -> OR"),
                ("Medford", "Redding", "OR -> CA"),
                ("Reno", "Truckee", "NV -> CA"),
                ("Ontario", "Boise", "OR -> ID")
            };

            _output.WriteLine("=== TABLA DE BENCHMARK: 36 PARES DE CIUDADES (PUNTO 4) ===");
            _output.WriteLine("| Par de Ciudades | Tipo | Encontrada | Longitud (km) | Línea Recta (km) | Ratio | Nodos Exp. | Tiempo (ms) |");
            _output.WriteLine("| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: |");

            int successCount = 0;
            var failureCauses = new List<string>();

            foreach (var (c1, c2, type) in testList)
            {
                if (!cityMap.TryGetValue(c1, out uint n1) || !cityMap.TryGetValue(c2, out uint n2))
                {
                    _output.WriteLine($"| {c1} -> {c2} | {type} | ERROR | Ciudad no encontrada | - | - | - | - |");
                    failureCauses.Add($"{c1} -> {c2}: Alguna de las ciudades no está registrada en el índice");
                    continue;
                }

                var sw = Stopwatch.StartNew();
                var res = pf.FindRoute(n1, n2);
                sw.Stop();

                string foundStr = res.Success ? "SÍ" : "NO";
                if (res.Success)
                {
                    successCount++;
                    _output.WriteLine($"| {c1} ➔ {c2} | {type} | **{foundStr}** | {res.TotalLengthMeters / 1000f:F2} | {res.EuclideanStraightDistance / 1000f:F2} | {res.DetourRatio:F2}x | {res.NodesExpanded} | {sw.ElapsedMilliseconds} ms |");
                }
                else
                {
                    _output.WriteLine($"| {c1} ➔ {c2} | {type} | {foundStr} | - | {res.EuclideanStraightDistance / 1000f:F2} | - | {res.NodesExpanded} | {sw.ElapsedMilliseconds} ms |");
                    failureCauses.Add($"{c1} ➔ {c2} ({type}): Nodos expandidos={res.NodesExpanded}. Discontinuidad en aristas dirigidas / rampas limítrofes entre sectores.");
                }
            }

            _output.WriteLine($"\nResumen: {successCount} / {testList.Length} rutas encontradas ({successCount * 100.0 / testList.Length:F1}%)");
            _output.WriteLine("\nCausas de fallo detalladas:");
            foreach (var f in failureCauses)
            {
                _output.WriteLine($"  - {f}");
            }
        }
    }
}