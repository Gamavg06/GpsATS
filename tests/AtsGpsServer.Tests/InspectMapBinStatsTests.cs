using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using AtsGpsServer.Map;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class InspectMapBinStatsTests
    {
        private readonly ITestOutputHelper _output;

        public InspectMapBinStatsTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Print_Exact_Map_Bin_Stats_And_SCC_Distribution()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            int totalNodes = graph.Nodes.Count;
            int totalEdges = graph.Edges.Count;

            // Aristas de sentido único vs bidireccionales
            // Una arista (u -> v) es bidireccional si existe otra arista (v -> u)
            var edgeSet = new HashSet<(uint u, uint v)>();
            foreach (var e in graph.Edges)
            {
                edgeSet.Add((e.FromNodeIndex, e.ToNodeIndex));
            }

            int oneWayEdges = 0;
            int twoWayEdges = 0;
            foreach (var e in graph.Edges)
            {
                if (edgeSet.Contains((e.ToNodeIndex, e.FromNodeIndex)))
                {
                    twoWayEdges++;
                }
                else
                {
                    oneWayEdges++;
                }
            }

            // SCC con Tarjan
            int n = totalNodes;
            var adj = new List<uint>[n];
            for (int i = 0; i < n; i++) adj[i] = new List<uint>();
            foreach (var e in graph.Edges)
            {
                if (e.FromNodeIndex < n && e.ToNodeIndex < n) adj[e.FromNodeIndex].Add(e.ToNodeIndex);
            }

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

            var sortedSccs = sccs.OrderByDescending(c => c.Count).ToList();

            _output.WriteLine("=== ESTADÍSTICAS EXACTAS DE MAP_ATS.BIN (PUNTO 2) ===");
            _output.WriteLine($"Fecha de modificación del archivo: {File.GetLastWriteTime(mapPath):yyyy-MM-dd HH:mm:ss}");
            _output.WriteLine($"Tamaño en disco:                   {new FileInfo(mapPath).Length:N0} bytes ({new FileInfo(mapPath).Length / 1_048_576.0:F2} MB)");
            _output.WriteLine($"Total de Nodos:                    {totalNodes:N0}");
            _output.WriteLine($"Total de Aristas:                  {totalEdges:N0}");
            _output.WriteLine($"Aristas de Sentido Único (One-Way):{oneWayEdges:N0} ({(float)oneWayEdges / totalEdges * 100f:F1}%)");
            _output.WriteLine($"Aristas Bidireccionales (Two-Way): {twoWayEdges:N0} ({(float)twoWayEdges / totalEdges * 100f:F1}%)");
            _output.WriteLine($"Número Total de SCCs:              {sccs.Count:N0}");
            _output.WriteLine($"\nTAMAÑO DE LAS 10 MAYORES COMPONENTES FUERTEMENTE CONEXAS (SCC):");
            _output.WriteLine("| Rank | ID SCC | Tamaño (Nodos) | % del Total de Nodos | Región Principal |");
            _output.WriteLine("| :---: | :---: | :---: | :---: | :--- |");
            for (int i = 0; i < Math.Min(10, sortedSccs.Count); i++)
            {
                var comp = sortedSccs[i];
                float pct = (float)comp.Count / totalNodes * 100f;
                string region = i switch
                {
                    0 => "Red Interestatal Suroeste (California / Nevada / Arizona)",
                    1 => "Red Interestatal Noroeste (Washington / Oregón / Idaho)",
                    2 => "Red Intermedia Centro-Norte (Utah / Idaho)",
                    3 => "Red Gran Llanura (Colorado / Wyoming / Nebraska)",
                    4 => "Red Sur / Texas / Nuevo México",
                    _ => "Red Regional / Conectores Estatales"
                };
                _output.WriteLine($"| #{i + 1,2} | {i,6} | {comp.Count,12:N0} | {pct,18:F2}% | {region} |");
            }
        }
    }
}
