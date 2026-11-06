using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using AtsGpsServer.Map;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class SccCityAnchorRepairTests
    {
        private readonly ITestOutputHelper _output;

        public SccCityAnchorRepairTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Find_Best_Scc_Anchors_For_All_Cities()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            int n = graph.Nodes.Count;
            var adj = new List<uint>[n];
            for (int i = 0; i < n; i++) adj[i] = new List<uint>();
            foreach (var e in graph.Edges)
            {
                if (e.FromNodeIndex < n && e.ToNodeIndex < n) adj[e.FromNodeIndex].Add(e.ToNodeIndex);
            }

            // Tarjan's Strongly Connected Components
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

            int reanchoredCount = 0;
            _output.WriteLine("=== REANCLAJE DE CIUDADES HACIA EL MAYOR COMPONENTE CONEXO ===");

            foreach (var city in graph.Cities)
            {
                uint curIdx = city.NearestNodeIndex;
                int curSccSize = nodeToSccSize[curIdx];

                // Buscar el nodo en un radio de hasta 4 km que pertenezca a la componente más grande y tenga In > 0, Out > 0
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

                if (bestIdx != curIdx && bestSccSize > curSccSize)
                {
                    reanchoredCount++;
                    _output.WriteLine($"  {city.Name}: Reanclado Nodo {curIdx} (SCC: {curSccSize} nodos) -> Nodo {bestIdx} (SCC: {bestSccSize} nodos, Dist: {bestDist:F0}m)");
                }
            }

            _output.WriteLine($"\nTotal ciudades que estaban mal ancladas en SCCs enanas/aisladas: {reanchoredCount} / {graph.Cities.Count} ({(float)reanchoredCount / graph.Cities.Count * 100f:F1}%)");
        }
    }
}