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
    public class InspectDetourPathsTests
    {
        private readonly ITestOutputHelper _output;

        public InspectDetourPathsTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Print_Detour_Routes_Every_1km()
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

            // Tarjan SCC
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

            var pf = new PathFinder(graph);

            var detourPairs = new (string c1, string c2)[]
            {
                ("Portland", "Salem"),
                ("Oakland", "San_jose"),
                ("Portland", "Bend")
            };

            foreach (var (c1, c2) in detourPairs)
            {
                uint n1 = cityMap[c1];
                uint n2 = cityMap[c2];
                var res = pf.FindRoute(n1, n2);

                _output.WriteLine($"\n================================================================================");
                _output.WriteLine($"DETALLE DE DESVÍO: {c1} (Nodo {n1}) ➔ {c2} (Nodo {n2})");
                _output.WriteLine($"  Longitud total ruta: {res.TotalLengthMeters / 1000f:F2} km");
                _output.WriteLine($"  Distancia euclídea:   {res.EuclideanStraightDistance / 1000f:F2} km");
                _output.WriteLine($"  Ratio de desvío:     {res.DetourRatio:F2}x");
                _output.WriteLine($"  Total aristas:       {res.EdgeCount}");
                _output.WriteLine($"--------------------------------------------------------------------------------");
                _output.WriteLine($"Muestreo de Waypoints cada 1.0 km a lo largo del recorrido:");

                float accumulated = 0f;
                float nextMilestone = 0f;
                int stepIndex = 0;

                var origCity = graph.Cities.First(c => c.Name.Equals(c1, StringComparison.OrdinalIgnoreCase));
                var destCity = graph.Cities.First(c => c.Name.Equals(c2, StringComparison.OrdinalIgnoreCase));

                for (int i = 0; i < res.EdgePath.Count; i++)
                {
                    var edge = res.EdgePath[i];
                    for (int p = 0; p < edge.Polyline.Count - 1; p++)
                    {
                        var p1 = edge.Polyline[p];
                        var p2 = edge.Polyline[p + 1];
                        float segLen = Vector3.Distance(new Vector3(p1.X, p1.Y, p1.Z), new Vector3(p2.X, p2.Y, p2.Z));

                        while (accumulated + segLen >= nextMilestone)
                        {
                            float t = segLen > 1e-4f ? (nextMilestone - accumulated) / segLen : 0f;
                            float curX = p1.X + t * (p2.X - p1.X);
                            float curY = p1.Y + t * (p2.Y - p1.Y);
                            float curZ = p1.Z + t * (p2.Z - p1.Z);

                            // Distancia hacia el destino
                            float distToDest = MathF.Sqrt(MathF.Pow(curX - destCity.X, 2) + MathF.Pow(curZ - destCity.Z, 2));

                            string note = "";
                            if (nextMilestone > 0 && distToDest > res.EuclideanStraightDistance * 1.2f)
                            {
                                note = " <<-- [ZONA DE DESVÍO/ALEJAMIENTO DE LA RUTA DIRECTA]";
                            }

                            _output.WriteLine($"  [Km {nextMilestone / 1000f,5:F1}] Coord: ({curX,9:F1}, {curZ,9:F1}) | Dist al Destino: {distToDest / 1000f,5:F2} km | Arista #{edge.EdgeId} (Tipo {edge.RoadType}){note}");

                            nextMilestone += 1000f;
                            stepIndex++;
                            if (nextMilestone > res.TotalLengthMeters) break;
                        }
                        accumulated += segLen;
                    }
                }
            }
        }
    }
}
