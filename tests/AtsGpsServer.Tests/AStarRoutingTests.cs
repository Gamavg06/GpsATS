using System;
using System.Collections.Generic;
using System.Diagnostics;
using AtsGpsServer.Map;
using Xunit;

namespace AtsGpsServer.Tests
{
    public class AStarRoutingTests
    {
        [Fact]
        public void AStar_CalculatesOptimalPathAndRespectsOneWay()
        {
            // Grafo sintético:
            // Node 0 -> Node 1 (One-way forward)
            // Node 1 -> Node 2 (Short road)
            // Node 0 -> Node 3 -> Node 2 (Long bypass)
            // Node 2 -> Node 0 (One-way reverse only via bypass)

            var graph = new MapGraph();
            graph.Nodes.Add(new MapNode { Index = 0, X = 0, Y = 0, Z = 0 });
            graph.Nodes.Add(new MapNode { Index = 1, X = 100, Y = 0, Z = 0 });
            graph.Nodes.Add(new MapNode { Index = 2, X = 200, Y = 0, Z = 0 });
            graph.Nodes.Add(new MapNode { Index = 3, X = 100, Y = 0, Z = 100 });

            // Ruta corta: 0 -> 1 -> 2 (costo 200)
            graph.Edges.Add(new MapEdge { EdgeId = 0, FromNodeIndex = 0, ToNodeIndex = 1, Length = 100f, SpeedLimitKmh = 100f });
            graph.Edges.Add(new MapEdge { EdgeId = 1, FromNodeIndex = 1, ToNodeIndex = 2, Length = 100f, SpeedLimitKmh = 100f });

            // Ruta larga bypass: 0 -> 3 -> 2 (costo ~282)
            graph.Edges.Add(new MapEdge { EdgeId = 2, FromNodeIndex = 0, ToNodeIndex = 3, Length = 141.4f, SpeedLimitKmh = 100f });
            graph.Edges.Add(new MapEdge { EdgeId = 3, FromNodeIndex = 3, ToNodeIndex = 2, Length = 141.4f, SpeedLimitKmh = 100f });

            // Warmup JIT
            FindPathAStar(graph, 0, 1);

            var sw = Stopwatch.StartNew();
            var path = FindPathAStar(graph, 0, 2);
            sw.Stop();

            // Debe elegir la ruta más corta: 0 -> 1 -> 2
            Assert.NotNull(path);
            Assert.Equal(3, path.Count);
            Assert.Equal(0u, path[0]);
            Assert.Equal(1u, path[1]);
            Assert.Equal(2u, path[2]);
            Assert.True(sw.ElapsedMilliseconds < 5, $"El cálculo de A* en caliente debe ser inferior a 5ms. Medido: {sw.ElapsedMilliseconds}ms");

            // Si intentamos navegar en sentido inverso: 2 -> 0
            // Dado que las aristas son dirigidas hacia adelante (one-way), 2 -> 0 no debe tener camino
            var reversePath = FindPathAStar(graph, 2, 0);
            Assert.Null(reversePath);
        }

        private static List<uint>? FindPathAStar(MapGraph graph, uint startNode, uint targetNode)
        {
            var openSet = new PriorityQueue<uint, float>();
            var cameFrom = new Dictionary<uint, uint>();
            var gScore = new Dictionary<uint, float>();

            // Construir lista de adyacencia
            var adjacency = new Dictionary<uint, List<MapEdge>>();
            foreach (var edge in graph.Edges)
            {
                if (!adjacency.TryGetValue(edge.FromNodeIndex, out var list))
                {
                    list = new List<MapEdge>();
                    adjacency[edge.FromNodeIndex] = list;
                }
                list.Add(edge);
            }

            gScore[startNode] = 0f;
            openSet.Enqueue(startNode, Heuristic(graph, startNode, targetNode));

            while (openSet.Count > 0)
            {
                uint current = openSet.Dequeue();
                if (current == targetNode)
                {
                    // Reconstruir camino
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
                    float tentativeG = gScore[current] + edge.Length;

                    if (!gScore.TryGetValue(neighbor, out float currentG) || tentativeG < currentG)
                    {
                        cameFrom[neighbor] = current;
                        gScore[neighbor] = tentativeG;
                        float fScore = tentativeG + Heuristic(graph, neighbor, targetNode);
                        openSet.Enqueue(neighbor, fScore);
                    }
                }
            }

            return null; // No hay camino alcanzable
        }

        private static float Heuristic(MapGraph graph, uint a, uint b)
        {
            var na = graph.Nodes[(int)a];
            var nb = graph.Nodes[(int)b];
            float dx = na.X - nb.X;
            float dz = na.Z - nb.Z;
            return (float)Math.Sqrt(dx * dx + dz * dz);
        }
    }
}
