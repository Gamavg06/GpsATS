using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Numerics;
using AtsGpsServer.Map;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class FailingPairsDeepInspectionTests
    {
        private readonly ITestOutputHelper _output;

        public FailingPairsDeepInspectionTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Inspect_All_Failing_Pairs_Gap_And_Closest_Node()
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

            // Tarjan SCC para tener los anclajes de las ciudades
            var indices = new int[n]; Array.Fill(indices, -1);
            var lowlink = new int[n];
            var onStack = new bool[n];
            var stack = new Stack<int>();
            int idxCounter = 0;
            var sccs = new List<List<int>>();

            for (int u = 0; u < n; u++) if (indices[u] == -1) StrongConnect(u);

            void StrongConnect(int v)
            {
                indices[v] = idxCounter; lowlink[v] = idxCounter; idxCounter++;
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
            for (int i = 0; i < sccs.Count; i++)
            {
                int sz = sccs[i].Count;
                foreach (var node in sccs[i]) nodeToSccSize[node] = sz;
            }

            var cityMap = new Dictionary<string, uint>(StringComparer.OrdinalIgnoreCase);
            foreach (var city in graph.Cities)
            {
                uint bestIdx = city.NearestNodeIndex;
                int bestSccSize = nodeToSccSize[bestIdx];
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

            var failingPairs = new (string c1, string c2)[]
            {
                ("Eugene", "Medford"),
                ("Bend", "Burns"),
                ("Twin_falls", "Pocatello"),
                ("Ontario", "Boise")
            };

            _output.WriteLine("=== ANÁLISIS EXHAUSTIVO DE LOS 4 PARES QUE NO CONECTAN (PUNTO 4) ===");

            foreach (var (c1, c2) in failingPairs)
            {
                if (!cityMap.TryGetValue(c1, out uint n1) || !cityMap.TryGetValue(c2, out uint n2))
                {
                    _output.WriteLine($"ERROR: {c1} o {c2} no encontradas en el índice.");
                    continue;
                }

                var originNode = graph.Nodes[(int)n1];
                var targetNode = graph.Nodes[(int)n2];
                float straightDist = Vector3.Distance(new Vector3(originNode.X, originNode.Y, originNode.Z), new Vector3(targetNode.X, targetNode.Y, targetNode.Z));

                // Realizar BFS / Dijkstra desde Origin para encontrar TODOS los nodos alcanzables
                var visited = new HashSet<uint>();
                var queue = new Queue<uint>();
                visited.Add(n1);
                queue.Enqueue(n1);

                while (queue.Count > 0)
                {
                    var u = queue.Dequeue();
                    foreach (var v in adj[u])
                    {
                        if (visited.Add(v))
                        {
                            queue.Enqueue(v);
                        }
                    }
                }

                bool reachable = visited.Contains(n2);

                // Encontrar el nodo alcanzable más cercano a n2
                uint closestReachable = n1;
                float closestDist = float.MaxValue;
                foreach (var nodeIdx in visited)
                {
                    var node = graph.Nodes[(int)nodeIdx];
                    float d = Vector3.Distance(new Vector3(node.X, node.Y, node.Z), new Vector3(targetNode.X, targetNode.Y, targetNode.Z));
                    if (d < closestDist)
                    {
                        closestDist = d;
                        closestReachable = nodeIdx;
                    }
                }

                var crNode = graph.Nodes[(int)closestReachable];

                _output.WriteLine($"\n------------------------------------------------------------");
                _output.WriteLine($"PAR: {c1} (Nodo {n1}) ➔ {c2} (Nodo {n2})");
                _output.WriteLine($"  Distancia Euclídea Total: {straightDist / 1000f:F2} km ({straightDist:F0} m)");
                _output.WriteLine($"  Total Nodos Alcanzables desde {c1}: {visited.Count}");
                _output.WriteLine($"  Destino Alcanzable: {(reachable ? "SÍ" : "NO")}");
                _output.WriteLine($"  NODO ALCANZABLE MÁS CERCANO AL DESTINO:");
                _output.WriteLine($"    Índice:      {closestReachable}");
                _output.WriteLine($"    UID Hex:     0x{crNode.Uid:X16}");
                _output.WriteLine($"    Coordenadas: X={crNode.X:F2}, Y={crNode.Y:F2}, Z={crNode.Z:F2}");
                _output.WriteLine($"    Distancia del 'Hueco' (Gap) al nodo destino: {closestDist:F1} m ({closestDist / 1000f:F2} km)");
                _output.WriteLine($"    Grado Salida del nodo frontera: {adj[closestReachable].Count}");
            }
        }
    }
}
