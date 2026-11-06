using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using AtsGpsServer.Map;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class GraphConnectivityTests
    {
        private readonly ITestOutputHelper _output;

        public GraphConnectivityTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Analyze_Connected_Components_Bend_Portland_Seattle()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            if (!File.Exists(mapPath))
            {
                _output.WriteLine("[OMITIDO] No existe map_ats.bin en server/data/map_ats.bin");
                return;
            }

            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            _output.WriteLine($"Grafo: {graph.Nodes.Count} nodos, {graph.Edges.Count} aristas, {graph.Cities.Count} ciudades");

            // Grafo no dirigido para componentes conexas (conectividad estructural de la red)
            var adjUndirected = new Dictionary<uint, HashSet<uint>>();
            foreach (var e in graph.Edges)
            {
                if (!adjUndirected.TryGetValue(e.FromNodeIndex, out var s1)) { s1 = new HashSet<uint>(); adjUndirected[e.FromNodeIndex] = s1; }
                s1.Add(e.ToNodeIndex);
                if (!adjUndirected.TryGetValue(e.ToNodeIndex, out var s2)) { s2 = new HashSet<uint>(); adjUndirected[e.ToNodeIndex] = s2; }
                s2.Add(e.FromNodeIndex);
            }

            var nodeComponent = new Dictionary<uint, int>();
            var componentSizes = new Dictionary<int, int>();
            int currentCompId = 0;

            for (uint i = 0; i < (uint)graph.Nodes.Count; i++)
            {
                if (nodeComponent.ContainsKey(i)) continue;
                currentCompId++;
                int size = 0;
                var q = new Queue<uint>();
                q.Enqueue(i);
                nodeComponent[i] = currentCompId;

                while (q.Count > 0)
                {
                    uint curr = q.Dequeue();
                    size++;
                    if (adjUndirected.TryGetValue(curr, out var neighbors))
                    {
                        foreach (var n in neighbors)
                        {
                            if (!nodeComponent.ContainsKey(n))
                            {
                                nodeComponent[n] = currentCompId;
                                q.Enqueue(n);
                            }
                        }
                    }
                }
                componentSizes[currentCompId] = size;
            }

            var top10 = componentSizes.OrderByDescending(kv => kv.Value).Take(10).ToList();
            int totalNodes = graph.Nodes.Count;
            int largestSize = top10[0].Value;
            double percentInLargest = (largestSize / (double)totalNodes) * 100.0;

            _output.WriteLine($"=== ANALISIS DE CONECTIVIDAD DEL GRAFO (B.1) ===");
            _output.WriteLine($"Total de componentes conexas: {componentSizes.Count}");
            _output.WriteLine($"Tamaño del mayor componente: {largestSize} nodos ({percentInLargest:F2}% del total)");
            _output.WriteLine($"Top 10 componentes mayores:");
            for (int k = 0; k < top10.Count; k++)
            {
                _output.WriteLine($"  #{k + 1}: CompID {top10[k].Key} -> {top10[k].Value} nodos ({(top10[k].Value / (double)totalNodes) * 100.0:F2}%)");
            }

            var cityBend = graph.Cities.FirstOrDefault(c => c.Name.Equals("Bend", StringComparison.OrdinalIgnoreCase));
            var cityPortland = graph.Cities.FirstOrDefault(c => c.Name.Equals("Portland", StringComparison.OrdinalIgnoreCase));
            var citySeattle = graph.Cities.FirstOrDefault(c => c.Name.Equals("Seattle", StringComparison.OrdinalIgnoreCase));

            if (cityBend != null)
            {
                int cBend = nodeComponent.GetValueOrDefault(cityBend.NearestNodeIndex, -1);
                int sizeBend = componentSizes.GetValueOrDefault(cBend, 0);
                _output.WriteLine($"Bend (Nodo {cityBend.NearestNodeIndex}): Pertenece a CompID {cBend} (tamaño: {sizeBend} nodos)");
            }
            if (cityPortland != null)
            {
                int cPort = nodeComponent.GetValueOrDefault(cityPortland.NearestNodeIndex, -1);
                int sizePort = componentSizes.GetValueOrDefault(cPort, 0);
                _output.WriteLine($"Portland (Nodo {cityPortland.NearestNodeIndex}): Pertenece a CompID {cPort} (tamaño: {sizePort} nodos)");
            }
            if (citySeattle != null)
            {
                int cSeat = nodeComponent.GetValueOrDefault(citySeattle.NearestNodeIndex, -1);
                int sizeSeat = componentSizes.GetValueOrDefault(cSeat, 0);
                _output.WriteLine($"Seattle (Nodo {citySeattle.NearestNodeIndex}): Pertenece a CompID {cSeat} (tamaño: {sizeSeat} nodos)");
            }
        }
    }
}
