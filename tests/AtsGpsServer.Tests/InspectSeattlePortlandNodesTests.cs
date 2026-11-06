using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using AtsGpsServer.Map;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class InspectSeattlePortlandNodesTests
    {
        private readonly ITestOutputHelper _output;

        public InspectSeattlePortlandNodesTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Inspect_Seattle_And_Portland_Nodes()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            var seattle = graph.Cities.FirstOrDefault(c => c.Name.Equals("Seattle", StringComparison.OrdinalIgnoreCase));
            var portland = graph.Cities.FirstOrDefault(c => c.Name.Equals("Portland", StringComparison.OrdinalIgnoreCase));

            Assert.NotNull(seattle);
            Assert.NotNull(portland);

            var inEdges = new Dictionary<uint, List<MapEdge>>();
            var outEdges = new Dictionary<uint, List<MapEdge>>();
            foreach (var e in graph.Edges)
            {
                if (!outEdges.TryGetValue(e.FromNodeIndex, out var oList)) { oList = new List<MapEdge>(); outEdges[e.FromNodeIndex] = oList; }
                oList.Add(e);
                if (!inEdges.TryGetValue(e.ToNodeIndex, out var iList)) { iList = new List<MapEdge>(); inEdges[e.ToNodeIndex] = iList; }
                iList.Add(e);
            }

            _output.WriteLine($"=== NODO ACTUAL DE SEATTLE: {seattle.NearestNodeIndex} ===");
            var sNode = graph.Nodes[(int)seattle.NearestNodeIndex];
            _output.WriteLine($"  Pos: ({sNode.X:F1}, {sNode.Z:F1}), Dist a CityCenter: {MathF.Sqrt(MathF.Pow(sNode.X - seattle.X, 2) + MathF.Pow(sNode.Z - seattle.Z, 2)):F1} m");
            outEdges.TryGetValue(seattle.NearestNodeIndex, out var sOut);
            inEdges.TryGetValue(seattle.NearestNodeIndex, out var sIn);
            _output.WriteLine($"  In-degree: {sIn?.Count ?? 0}, Out-degree: {sOut?.Count ?? 0}");
            if (sOut != null) foreach (var e in sOut) _output.WriteLine($"    OutEdge {e.EdgeId} -> ToNode {e.ToNodeIndex} (Len: {e.Length:F1}m, Type: {e.RoadType})");
            if (sIn != null) foreach (var e in sIn) _output.WriteLine($"    InEdge {e.EdgeId} <- FromNode {e.FromNodeIndex} (Len: {e.Length:F1}m, Type: {e.RoadType})");

            _output.WriteLine($"\n=== NODO ACTUAL DE PORTLAND: {portland.NearestNodeIndex} ===");
            var pNode = graph.Nodes[(int)portland.NearestNodeIndex];
            _output.WriteLine($"  Pos: ({pNode.X:F1}, {pNode.Z:F1}), Dist a CityCenter: {MathF.Sqrt(MathF.Pow(pNode.X - portland.X, 2) + MathF.Pow(pNode.Z - portland.Z, 2)):F1} m");
            outEdges.TryGetValue(portland.NearestNodeIndex, out var pOut);
            inEdges.TryGetValue(portland.NearestNodeIndex, out var pIn);
            _output.WriteLine($"  In-degree: {pIn?.Count ?? 0}, Out-degree: {pOut?.Count ?? 0}");
            if (pOut != null) foreach (var e in pOut) _output.WriteLine($"    OutEdge {e.EdgeId} -> ToNode {e.ToNodeIndex} (Len: {e.Length:F1}m, Type: {e.RoadType})");
            if (pIn != null) foreach (var e in pIn) _output.WriteLine($"    InEdge {e.EdgeId} <- FromNode {e.FromNodeIndex} (Len: {e.Length:F1}m, Type: {e.RoadType})");

            // Buscar mejores nodos candidatos en Seattle con In >= 1 y Out >= 1
            _output.WriteLine($"\n=== CANDIDATOS TRANSITABLES EN SEATTLE (radio 3km) ===");
            var candidates = new List<(uint idx, float dist, int ind, int outd)>();
            for (int i = 0; i < graph.Nodes.Count; i++)
            {
                var n = graph.Nodes[i];
                float d = MathF.Sqrt(MathF.Pow(n.X - seattle.X, 2) + MathF.Pow(n.Z - seattle.Z, 2));
                if (d < 3000f)
                {
                    int ind = inEdges.TryGetValue((uint)i, out var il) ? il.Count : 0;
                    int outd = outEdges.TryGetValue((uint)i, out var ol) ? ol.Count : 0;
                    if (ind >= 1 && outd >= 1)
                    {
                        candidates.Add(((uint)i, d, ind, outd));
                    }
                }
            }
            candidates.Sort((a, b) => a.dist.CompareTo(b.dist));
            foreach (var c in candidates.Take(5))
            {
                _output.WriteLine($"  Candidato Nodo {c.idx}: Dist={c.dist:F1}m, In={c.ind}, Out={c.outd}");
            }
        }
    }
}