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
    public class ReconcileSccContradictionTests
    {
        private readonly ITestOutputHelper _output;

        public ReconcileSccContradictionTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Reconcile_Portland_Seattle_Scc_And_Benchmark()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            int n = graph.Nodes.Count;
            var inDegree = new int[n];
            var outDegree = new int[n];
            var adj = new List<int>[n];
            for (int i = 0; i < n; i++) adj[i] = new List<int>();

            foreach (var e in graph.Edges)
            {
                if (e.FromNodeIndex < n && e.ToNodeIndex < n)
                {
                    outDegree[e.FromNodeIndex]++;
                    inDegree[e.ToNodeIndex]++;
                    adj[e.FromNodeIndex].Add((int)e.ToNodeIndex);
                }
            }

            // 1. Tarjan SCC
            int index = 0;
            var indices = new int[n];
            var lowlink = new int[n];
            var onStack = new bool[n];
            Array.Fill(indices, -1);
            var stack = new Stack<int>();
            var sccList = new List<List<int>>();

            for (int i = 0; i < n; i++)
            {
                if (indices[i] == -1)
                {
                    StrongConnect(i, ref index, indices, lowlink, onStack, stack, sccList, adj);
                }
            }

            var nodeToScc = new int[n];
            for (int sccId = 0; sccId < sccList.Count; sccId++)
            {
                foreach (int u in sccList[sccId])
                {
                    nodeToScc[u] = sccId;
                }
            }

            _output.WriteLine($"Total SCCs calculadas: {sccList.Count}");
            var topSccs = sccList.Select((comp, id) => new { Id = id, Count = comp.Count })
                                 .OrderByDescending(x => x.Count)
                                 .Take(10)
                                 .ToList();

            for (int r = 0; r < topSccs.Count; r++)
            {
                _output.WriteLine($"  Top #{r + 1}: SCC ID {topSccs[r].Id} con {topSccs[r].Count} nodos ({(topSccs[r].Count * 100.0 / n):F2}%)");
            }

            // 2. Investigar nodos de Portland, Seattle y Bend
            var portlandCity = graph.Cities.First(c => c.Name.Equals("Portland", StringComparison.OrdinalIgnoreCase));
            var seattleCity = graph.Cities.First(c => c.Name.Equals("Seattle", StringComparison.OrdinalIgnoreCase));
            var bendCity = graph.Cities.First(c => c.Name.Equals("Bend", StringComparison.OrdinalIgnoreCase));

            // Nodo nativo del archivo
            uint pRaw = portlandCity.NearestNodeIndex;
            uint sRaw = seattleCity.NearestNodeIndex;
            uint bRaw = bendCity.NearestNodeIndex;

            // Nodo elegido por el algoritmo de Benchmark30PairsTests
            uint pBench = FindBenchmarkNode(graph, portlandCity, inDegree, outDegree);
            uint sBench = FindBenchmarkNode(graph, seattleCity, inDegree, outDegree);
            uint bBench = FindBenchmarkNode(graph, bendCity, inDegree, outDegree);

            // Nodos usados en ReanchoredRoutingTests
            uint pManual = 23308;
            uint sManual = 32562;
            uint bManual = 34861;

            _output.WriteLine("\n=== COMPARACIÓN DE ÍNDICES DE NODO Y SU SCC CORRESPONDIENTE ===");
            PrintNodeInfo("Portland RAW (archivo)", pRaw, graph, nodeToScc, sccList, inDegree, outDegree);
            PrintNodeInfo("Portland BENCH (Benchmark30Pairs)", pBench, graph, nodeToScc, sccList, inDegree, outDegree);
            PrintNodeInfo("Portland MANUAL (ReanchoredTests 23308)", pManual, graph, nodeToScc, sccList, inDegree, outDegree);

            PrintNodeInfo("Seattle RAW (archivo)", sRaw, graph, nodeToScc, sccList, inDegree, outDegree);
            PrintNodeInfo("Seattle BENCH (Benchmark30Pairs)", sBench, graph, nodeToScc, sccList, inDegree, outDegree);
            PrintNodeInfo("Seattle MANUAL (ReanchoredTests 32562)", sManual, graph, nodeToScc, sccList, inDegree, outDegree);

            PrintNodeInfo("Bend RAW (archivo)", bRaw, graph, nodeToScc, sccList, inDegree, outDegree);
            PrintNodeInfo("Bend BENCH (Benchmark30Pairs)", bBench, graph, nodeToScc, sccList, inDegree, outDegree);
            PrintNodeInfo("Bend MANUAL (ReanchoredTests 34861)", bManual, graph, nodeToScc, sccList, inDegree, outDegree);

            // 3. Ejecutar PathFinder para ver qué ocurre exactamente con cada par
            var pf = new PathFinder(graph);
            _output.WriteLine("\n=== PRUEBAS A* DIRECTAS ===");
            TestPair(pf, "BENCHMARK: Portland(pBench) -> Seattle(sBench)", pBench, sBench);
            TestPair(pf, "BENCHMARK: Seattle(sBench) -> Portland(pBench)", sBench, pBench);
            TestPair(pf, "MANUAL: Portland(23308) -> Seattle(32562)", pManual, sManual);
            TestPair(pf, "MANUAL: Seattle(32562) -> Portland(23308)", sManual, pManual);
        }

        private void TestPair(PathFinder pf, string label, uint from, uint to)
        {
            var sw = Stopwatch.StartNew();
            var res = pf.FindRoute(from, to);
            sw.Stop();
            _output.WriteLine($"{label}: Exito={res.Success}, NodosExpandidos={res.NodesExpanded}, Distancia={(res.Success ? res.TotalLengthMeters : 0):F0}m, Tiempo={sw.ElapsedMilliseconds}ms");
        }

        private void PrintNodeInfo(string label, uint nodeIdx, MapGraph graph, int[] nodeToScc, List<List<int>> sccList, int[] inDegree, int[] outDegree)
        {
            if (nodeIdx >= graph.Nodes.Count)
            {
                _output.WriteLine($"{label}: Índice {nodeIdx} fuera de rango!");
                return;
            }
            int sccId = nodeToScc[nodeIdx];
            int sccSize = sccList[sccId].Count;
            var node = graph.Nodes[(int)nodeIdx];
            _output.WriteLine($"{label}: Index={nodeIdx}, UID=0x{node.Uid:X16}, Coords=({node.X:F1}, {node.Z:F1}), In={inDegree[nodeIdx]}, Out={outDegree[nodeIdx]}, SCC_ID={sccId}, SCC_Size={sccSize}");
        }

        private uint FindBenchmarkNode(MapGraph graph, MapCity city, int[] inDegree, int[] outDegree)
        {
            uint bestIdx = city.NearestNodeIndex;
            float bestDist = float.MaxValue;
            bool foundTransitable = false;
            for (int i = 0; i < graph.Nodes.Count; i++)
            {
                var node = graph.Nodes[i];
                float dx = node.X - city.X;
                float dz = node.Z - city.Z;
                float dist = MathF.Sqrt(dx * dx + dz * dz);
                if (dist > 3500f) continue;
                if (inDegree[i] > 0 && outDegree[i] > 0)
                {
                    if (!foundTransitable || dist < bestDist)
                    {
                        foundTransitable = true;
                        bestDist = dist;
                        bestIdx = (uint)i;
                    }
                }
            }
            return bestIdx;
        }

        private void StrongConnect(int v, ref int index, int[] indices, int[] lowlink, bool[] onStack, Stack<int> stack, List<List<int>> sccList, List<int>[] adj)
        {
            indices[v] = index;
            lowlink[v] = index;
            index++;
            stack.Push(v);
            onStack[v] = true;

            foreach (int w in adj[v])
            {
                if (indices[w] == -1)
                {
                    StrongConnect(w, ref index, indices, lowlink, onStack, stack, sccList, adj);
                    lowlink[v] = Math.Min(lowlink[v], lowlink[w]);
                }
                else if (onStack[w])
                {
                    lowlink[v] = Math.Min(lowlink[v], indices[w]);
                }
            }

            if (lowlink[v] == indices[v])
            {
                var scc = new List<int>();
                while (true)
                {
                    int w = stack.Pop();
                    onStack[w] = false;
                    scc.Add(w);
                    if (w == v) break;
                }
                sccList.Add(scc);
            }
        }
    }
}
