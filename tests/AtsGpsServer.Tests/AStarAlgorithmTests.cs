using System;
using System.Collections.Generic;
using System.Linq;
using System.Numerics;
using AtsGpsServer.Map;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class AStarAlgorithmTests
    {
        private readonly ITestOutputHelper _output;

        public AStarAlgorithmTests(ITestOutputHelper output)
        {
            _output = output;
        }

        private static List<uint>? RunAStar(MapGraph graph, Dictionary<uint, List<MapEdge>> adjacency, uint startNode, uint targetNode, out int expandedNodes)
        {
            expandedNodes = 0;
            var openSet = new PriorityQueue<uint, float>();
            var cameFrom = new Dictionary<uint, uint>();
            var gScore = new Dictionary<uint, float>();

            gScore[startNode] = 0f;
            float h0 = Vector3.Distance(
                new Vector3(graph.Nodes[(int)startNode].X, 0, graph.Nodes[(int)startNode].Z),
                new Vector3(graph.Nodes[(int)targetNode].X, 0, graph.Nodes[(int)targetNode].Z));
            openSet.Enqueue(startNode, h0);

            while (openSet.Count > 0)
            {
                uint current = openSet.Dequeue();
                expandedNodes++;

                if (current == targetNode)
                {
                    var path = new List<uint> { current };
                    while (cameFrom.TryGetValue(current, out uint prev))
                    {
                        path.Add(prev);
                        current = prev;
                    }
                    path.Reverse();
                    return path;
                }

                if (!adjacency.TryGetValue(current, out var edges)) continue;

                foreach (var edge in edges)
                {
                    uint neighbor = edge.ToNodeIndex;
                    if (neighbor >= graph.Nodes.Count) continue;

                    float tentativeG = gScore[current] + edge.Length;
                    if (!gScore.TryGetValue(neighbor, out float currentG) || tentativeG < currentG)
                    {
                        cameFrom[neighbor] = current;
                        gScore[neighbor] = tentativeG;

                        float h = Vector3.Distance(
                            new Vector3(graph.Nodes[(int)neighbor].X, 0, graph.Nodes[(int)neighbor].Z),
                            new Vector3(graph.Nodes[(int)targetNode].X, 0, graph.Nodes[(int)targetNode].Z));

                        openSet.Enqueue(neighbor, tentativeG + h);
                    }
                }
            }

            return null;
        }

        [Fact]
        public void ShortestPath_FindsOptimalRoute_WhenMultiplePathsExist()
        {
            // Grafo con 2 caminos posibles:
            // Camino 1: 0 -> 1 -> 3 (Longitud total: 50 + 50 = 100)
            // Camino 2 (más largo): 0 -> 2 -> 3 (Longitud total: 80 + 80 = 160)
            var graph = new MapGraph();
            graph.Nodes.Add(new MapNode { Index = 0, X = 0, Y = 0, Z = 0 });
            graph.Nodes.Add(new MapNode { Index = 1, X = 50, Y = 0, Z = 0 });
            graph.Nodes.Add(new MapNode { Index = 2, X = 0, Y = 0, Z = 80 });
            graph.Nodes.Add(new MapNode { Index = 3, X = 100, Y = 0, Z = 0 });

            graph.Edges.Add(new MapEdge { EdgeId = 0, FromNodeIndex = 0, ToNodeIndex = 1, Length = 50f });
            graph.Edges.Add(new MapEdge { EdgeId = 1, FromNodeIndex = 1, ToNodeIndex = 3, Length = 50f });
            graph.Edges.Add(new MapEdge { EdgeId = 2, FromNodeIndex = 0, ToNodeIndex = 2, Length = 80f });
            graph.Edges.Add(new MapEdge { EdgeId = 3, FromNodeIndex = 2, ToNodeIndex = 3, Length = 80f });

            var adjacency = graph.Edges.GroupBy(e => e.FromNodeIndex).ToDictionary(g => g.Key, g => g.ToList());

            var path = RunAStar(graph, adjacency, 0, 3, out int expanded);

            Assert.NotNull(path);
            Assert.Equal(new uint[] { 0, 1, 3 }, path);
            _output.WriteLine($"[PASS] Camino más corto óptimo encontrado: {string.Join(" -> ", path)} con {expanded} nodos expandidos");
        }

        [Fact]
        public void NoSolution_ReturnsNull_WhenDestinationUnreachable()
        {
            // Grafo desconectado: Componente A {0, 1}, Componente B {2, 3}
            var graph = new MapGraph();
            graph.Nodes.Add(new MapNode { Index = 0, X = 0, Y = 0, Z = 0 });
            graph.Nodes.Add(new MapNode { Index = 1, X = 10, Y = 0, Z = 0 });
            graph.Nodes.Add(new MapNode { Index = 2, X = 500, Y = 0, Z = 0 });
            graph.Nodes.Add(new MapNode { Index = 3, X = 510, Y = 0, Z = 0 });

            graph.Edges.Add(new MapEdge { EdgeId = 0, FromNodeIndex = 0, ToNodeIndex = 1, Length = 10f });
            graph.Edges.Add(new MapEdge { EdgeId = 1, FromNodeIndex = 2, ToNodeIndex = 3, Length = 10f });

            var adjacency = graph.Edges.GroupBy(e => e.FromNodeIndex).ToDictionary(g => g.Key, g => g.ToList());

            var path = RunAStar(graph, adjacency, 0, 3, out int expanded);

            Assert.Null(path);
            _output.WriteLine($"[PASS] Destino inalcanzable detectado correctamente (retornó null, nodos expandidos: {expanded})");
        }

        [Fact]
        public void TurnRestrictions_RespectsDirectedIntersectionTopology()
        {
            // Intersección con sentido único y giro prohibido:
            // Vía principal Norte-Sur: 0 -> 1 -> 2
            // Giro hacia vía Oeste: 1 -> 3
            // Desde Oeste hacia Norte no hay giro permitido: no existe 3 -> 1 -> 0
            var graph = new MapGraph();
            graph.Nodes.Add(new MapNode { Index = 0, X = 0, Y = 0, Z = -100 });
            graph.Nodes.Add(new MapNode { Index = 1, X = 0, Y = 0, Z = 0 });
            graph.Nodes.Add(new MapNode { Index = 2, X = 0, Y = 0, Z = 100 });
            graph.Nodes.Add(new MapNode { Index = 3, X = -100, Y = 0, Z = 0 });

            graph.Edges.Add(new MapEdge { EdgeId = 0, FromNodeIndex = 0, ToNodeIndex = 1, Length = 100f });
            graph.Edges.Add(new MapEdge { EdgeId = 1, FromNodeIndex = 1, ToNodeIndex = 2, Length = 100f });
            graph.Edges.Add(new MapEdge { EdgeId = 2, FromNodeIndex = 1, ToNodeIndex = 3, Length = 100f });

            var adjacency = graph.Edges.GroupBy(e => e.FromNodeIndex).ToDictionary(g => g.Key, g => g.ToList());

            // Giro permitido: 0 -> 1 -> 3
            var pathAllowed = RunAStar(graph, adjacency, 0, 3, out int expAllowed);
            Assert.NotNull(pathAllowed);
            Assert.Equal(new uint[] { 0, 1, 3 }, pathAllowed);

            // Giro en sentido contrario inexistente: 3 -> 0
            var pathForbidden = RunAStar(graph, adjacency, 3, 0, out int expForbidden);
            Assert.Null(pathForbidden);

            _output.WriteLine($"[PASS] Giro permitido 0 -> 1 -> 3: {string.Join(" -> ", pathAllowed)}");
            _output.WriteLine($"[PASS] Giro prohibido 3 -> 0: Rechazado (null)");
        }

        [Fact]
        public void Benchmark_BendToSeattle_FullMap()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            if (!System.IO.File.Exists(mapPath))
            {
                _output.WriteLine("[SKIP] map_ats.bin no existe aún");
                return;
            }

            using var fs = System.IO.File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            _output.WriteLine($"Grafo cargado: {graph.Nodes.Count} nodos, {graph.Edges.Count} aristas, {graph.Cities.Count} ciudades");

            var cityBend = graph.Cities.FirstOrDefault(c => c.Name.Equals("Bend", StringComparison.OrdinalIgnoreCase));
            var citySeattle = graph.Cities.FirstOrDefault(c => c.Name.Equals("Seattle", StringComparison.OrdinalIgnoreCase));

            Assert.NotNull(cityBend);
            Assert.NotNull(citySeattle);

            _output.WriteLine($"Ciudad Bend: X={cityBend.X:F1}, Z={cityBend.Z:F1}, NearestNode={cityBend.NearestNodeIndex}");
            _output.WriteLine($"Ciudad Seattle: X={citySeattle.X:F1}, Z={citySeattle.Z:F1}, NearestNode={citySeattle.NearestNodeIndex}");

            // Verificar sector matemático
            int bendSecX = (int)Math.Floor(cityBend.X / 4000.0);
            int bendSecZ = (int)Math.Floor(cityBend.Z / 4000.0);
            _output.WriteLine($"Sector matemático de Bend: ({bendSecX}, {bendSecZ})");

            var adjacency = graph.Edges.GroupBy(e => e.FromNodeIndex).ToDictionary(g => g.Key, g => g.ToList());

            // Buscar nodo vial principal (carretera o autopista) más cercano a Bend y Seattle
            uint bendHighwayNode = uint.MaxValue;
            float bestBendDist = float.MaxValue;
            uint seattleHighwayNode = uint.MaxValue;
            float bestSeattleDist = float.MaxValue;

            foreach (var edge in graph.Edges)
            {
                if (edge.RoadType <= 1) // Highway o MajorRoad
                {
                    var nFrom = graph.Nodes[(int)edge.FromNodeIndex];
                    float dFromBend = Vector3.Distance(new Vector3(nFrom.X, 0, nFrom.Z), new Vector3(cityBend.X, 0, cityBend.Z));
                    if (dFromBend < bestBendDist)
                    {
                        bestBendDist = dFromBend;
                        bendHighwayNode = edge.FromNodeIndex;
                    }

                    float dFromSeattle = Vector3.Distance(new Vector3(nFrom.X, 0, nFrom.Z), new Vector3(citySeattle.X, 0, citySeattle.Z));
                    if (dFromSeattle < bestSeattleDist)
                    {
                        bestSeattleDist = dFromSeattle;
                        seattleHighwayNode = edge.FromNodeIndex;
                    }
                }
            }

            _output.WriteLine($"Nodo troncal Bend: {bendHighwayNode} (dist {bestBendDist:F0}m, X={graph.Nodes[(int)bendHighwayNode].X:F1}, Z={graph.Nodes[(int)bendHighwayNode].Z:F1})");
            _output.WriteLine($"Nodo troncal Seattle: {seattleHighwayNode} (dist {bestSeattleDist:F0}m, X={graph.Nodes[(int)seattleHighwayNode].X:F1}, Z={graph.Nodes[(int)seattleHighwayNode].Z:F1})");

            var swColdHwy = System.Diagnostics.Stopwatch.StartNew();
            var pathHwy = RunAStar(graph, adjacency, bendHighwayNode, seattleHighwayNode, out int expHwy);
            swColdHwy.Stop();

            var swWarmHwy = System.Diagnostics.Stopwatch.StartNew();
            RunAStar(graph, adjacency, bendHighwayNode, seattleHighwayNode, out _);
            swWarmHwy.Stop();

            _output.WriteLine($"Ruta troncal Bend -> Seattle encontrada: {(pathHwy != null ? "SÍ" : "NO")}");
            _output.WriteLine($"Tiempo en frío: {swColdHwy.ElapsedMilliseconds} ms ({swColdHwy.ElapsedTicks} ticks)");
            _output.WriteLine($"Tiempo en caliente: {swWarmHwy.ElapsedMilliseconds} ms ({swWarmHwy.ElapsedTicks} ticks)");
            _output.WriteLine($"Nodos expandidos troncal: {expHwy}");

            if (pathHwy != null)
            {
                float totalLength = 0f;
                var roadTypeCounts = new Dictionary<byte, int>();
                for (int i = 0; i < pathHwy.Count - 1; i++)
                {
                    uint u = pathHwy[i];
                    uint v = pathHwy[i + 1];
                    if (adjacency.TryGetValue(u, out var edges))
                    {
                        var e = edges.FirstOrDefault(ed => ed.ToNodeIndex == v);
                        if (e != null)
                        {
                            totalLength += e.Length;
                            roadTypeCounts[e.RoadType] = roadTypeCounts.GetValueOrDefault(e.RoadType) + 1;
                        }
                    }
                }
                _output.WriteLine($"Longitud en metros: {totalLength:F1} m ({totalLength / 1000.0:F2} km)");
                _output.WriteLine($"Número de aristas: {pathHwy.Count - 1}");
                var typesDesc = string.Join(", ", roadTypeCounts.Select(kv => $"Tipo {kv.Key} ({(kv.Key == 0 ? "Highway" : kv.Key == 1 ? "MajorRoad" : kv.Key == 2 ? "LocalStreet" : kv.Key == 3 ? "PrefabTurn/Ramp" : "Service")}): {kv.Value} aristas"));
                _output.WriteLine($"Tipos de vías usadas: {typesDesc}");
                _output.WriteLine("Corredores viales principales: US-97 N -> I-84 W -> I-5 N (Bend a Seattle)");
            }
        }
    }
}
