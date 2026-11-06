using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Numerics;

namespace AtsGpsServer.Map
{
    public class RouteResult
    {
        public bool Success { get; set; }
        public List<uint> NodePath { get; set; } = new();
        public List<MapEdge> EdgePath { get; set; } = new();
        public float TotalLengthMeters { get; set; }
        public int EdgeCount => EdgePath.Count;
        public int NodesExpanded { get; set; }
        public float EuclideanStraightDistance { get; set; }
        public float DetourRatio => EuclideanStraightDistance > 0 ? TotalLengthMeters / EuclideanStraightDistance : 1.0f;
        public long ExecutionTimeMicroseconds { get; set; }
        public List<string> RoadNamesUsed { get; set; } = new();
    }

    public class PathFinder
    {
        private readonly MapGraph _graph;
        private readonly Dictionary<uint, List<MapEdge>> _adjacency = new();
        private readonly HashSet<(uint fromNode, uint viaNode, uint toNode)> _forbiddenTurns = new();

        public PathFinder(MapGraph graph)
        {
            _graph = graph;
            BuildAdjacency();
        }

        private void BuildAdjacency()
        {
            foreach (var edge in _graph.Edges)
            {
                if (!_adjacency.TryGetValue(edge.FromNodeIndex, out var list))
                {
                    list = new List<MapEdge>();
                    _adjacency[edge.FromNodeIndex] = list;
                }
                list.Add(edge);
            }
        }

        public void AddForbiddenTurn(uint fromNode, uint viaNode, uint toNode)
        {
            _forbiddenTurns.Add((fromNode, viaNode, toNode));
        }

        public RouteResult FindRoute(uint startNodeIndex, uint targetNodeIndex)
        {
            var sw = Stopwatch.StartNew();

            if (startNodeIndex >= _graph.Nodes.Count || targetNodeIndex >= _graph.Nodes.Count)
            {
                return new RouteResult { Success = false };
            }

            var startNode = _graph.Nodes[(int)startNodeIndex];
            var targetNode = _graph.Nodes[(int)targetNodeIndex];
            float euclideanDist = Vector3.Distance(
                new Vector3(startNode.X, startNode.Y, startNode.Z),
                new Vector3(targetNode.X, targetNode.Y, targetNode.Z));

            if (startNodeIndex == targetNodeIndex)
            {
                sw.Stop();
                return new RouteResult
                {
                    Success = true,
                    NodePath = new List<uint> { startNodeIndex },
                    TotalLengthMeters = 0,
                    EuclideanStraightDistance = 0,
                    NodesExpanded = 0,
                    ExecutionTimeMicroseconds = sw.ElapsedTicks * 1_000_000 / Stopwatch.Frequency
                };
            }

            // A* expandido por aristas (line graph state: (currentNode, incomingNode))
            // para evaluar giros prohibidos (prevNode -> currentNode -> nextNode)
            var openSet = new PriorityQueue<(uint node, uint prevNode), float>();
            var cameFrom = new Dictionary<(uint node, uint prevNode), (uint node, uint prevNode)>();
            var cameFromEdge = new Dictionary<(uint node, uint prevNode), MapEdge>();
            var gScore = new Dictionary<(uint node, uint prevNode), float>();

            var initialKey = (node: startNodeIndex, prevNode: uint.MaxValue);
            gScore[initialKey] = 0f;
            openSet.Enqueue(initialKey, euclideanDist);

            int expandedCount = 0;
            (uint node, uint prevNode)? goalKey = null;

            while (openSet.Count > 0)
            {
                var current = openSet.Dequeue();
                expandedCount++;

                if (current.node == targetNodeIndex)
                {
                    goalKey = current;
                    break;
                }

                if (!_adjacency.TryGetValue(current.node, out var edges)) continue;

                var currNodeObj = _graph.Nodes[(int)current.node];

                foreach (var edge in edges)
                {
                    uint neighbor = edge.ToNodeIndex;
                    if (neighbor >= _graph.Nodes.Count) continue;

                    // Validación de giro prohibido: (prevNode -> current.node -> neighbor)
                    if (current.prevNode != uint.MaxValue && _forbiddenTurns.Contains((current.prevNode, current.node, neighbor)))
                    {
                        continue;
                    }

                    // Penalización de U-turn (no regresar inmediatamente por donde vino salvo que no haya alternativa)
                    float turnPenalty = 0f;
                    if (neighbor == current.prevNode)
                    {
                        turnPenalty = 1000f; // Fuerte desincentivo a U-turn inmediato
                    }

                    var neighborKey = (node: neighbor, prevNode: current.node);
                    float tentativeG = gScore[current] + edge.Length + turnPenalty;

                    if (!gScore.TryGetValue(neighborKey, out float curG) || tentativeG < curG)
                    {
                        cameFrom[neighborKey] = current;
                        cameFromEdge[neighborKey] = edge;
                        gScore[neighborKey] = tentativeG;

                        var neighborObj = _graph.Nodes[(int)neighbor];
                        float h = Vector3.Distance(
                            new Vector3(neighborObj.X, neighborObj.Y, neighborObj.Z),
                            new Vector3(targetNode.X, targetNode.Y, targetNode.Z));

                        openSet.Enqueue(neighborKey, tentativeG + h);
                    }
                }
            }

            sw.Stop();
            long execMicros = sw.ElapsedTicks * 1_000_000 / Stopwatch.Frequency;

            if (!goalKey.HasValue)
            {
                return new RouteResult
                {
                    Success = false,
                    NodesExpanded = expandedCount,
                    EuclideanStraightDistance = euclideanDist,
                    ExecutionTimeMicroseconds = execMicros
                };
            }

            // Reconstrucción de ruta
            var nodePath = new List<uint>();
            var edgePath = new List<MapEdge>();
            var roadNames = new HashSet<string>();

            var currState = goalKey.Value;
            nodePath.Add(currState.node);

            while (cameFrom.TryGetValue(currState, out var prevState))
            {
                if (cameFromEdge.TryGetValue(currState, out var edge))
                {
                    edgePath.Add(edge);
                    string roadDesc = edge.RoadType switch
                    {
                        0 => "Interstate / Highway",
                        1 => "US Highway / Major Road",
                        2 => "Local Street",
                        3 => "Prefab Turn / Interchange Ramp",
                        4 => "Service Depot / Company Road",
                        _ => "Road"
                    };
                    roadNames.Add(roadDesc);
                }

                currState = prevState;
                nodePath.Add(currState.node);
            }

            nodePath.Reverse();
            edgePath.Reverse();

            float totalLength = edgePath.Sum(e => e.Length);

            return new RouteResult
            {
                Success = true,
                NodePath = nodePath,
                EdgePath = edgePath,
                TotalLengthMeters = totalLength,
                NodesExpanded = expandedCount,
                EuclideanStraightDistance = euclideanDist,
                ExecutionTimeMicroseconds = execMicros,
                RoadNamesUsed = roadNames.ToList()
            };
        }
    }
}
