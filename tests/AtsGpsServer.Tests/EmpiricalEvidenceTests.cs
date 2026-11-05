using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Numerics;
using AtsGpsServer.Map;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class EmpiricalEvidenceTests
    {
        private readonly ITestOutputHelper _output;

        public EmpiricalEvidenceTests(ITestOutputHelper output)
        {
            _output = output;
        }

        private MapGraph? LoadFullMapIfAvailable()
        {
            string[] paths =
            [
                @"C:\Telemetriagps\server\data\map_ats.bin",
                @"C:\Telemetriagps\server\bin\Release\net10.0-windows\data\map_ats.bin",
                @"C:\Telemetriagps\server\bin\Debug\net10.0-windows\data\map_ats.bin"
            ];

            foreach (var p in paths)
            {
                if (File.Exists(p))
                {
                    using var fs = File.OpenRead(p);
                    return MapBinarySerializer.Deserialize(fs);
                }
            }

            return null;
        }

        [Fact]
        public void C1_AStar_Benchmark_FullMap_MeasuresTimeAndMemory()
        {
            var graph = LoadFullMapIfAvailable();
            if (graph == null || graph.Nodes.Count == 0)
            {
                _output.WriteLine("[OMITIDO] No se encontró map_ats.bin local para benchmarking de grafo completo.");
                return;
            }

            // Buscar nodo más cercano a Bend (aprox X: -84000, Z: -36000)
            uint bendNode = FindNearestNode(graph, -84000f, -36000f);
            // Buscar nodo más cercano a Seattle (aprox X: -88000, Z: -59000) o Los Angeles (aprox X: -80000, Z: 12000)
            uint targetNode = FindNearestNode(graph, -88000f, -59000f);

            // Construir lista de adyacencia una sola vez
            var adjacency = BuildAdjacency(graph);

            // Medición de memoria antes
            GC.Collect();
            GC.WaitForPendingFinalizers();
            GC.Collect();
            long memBefore = GC.GetTotalMemory(true);

            // Cronometrar A*
            var sw = Stopwatch.StartNew();
            var path = ExecuteAStar(graph, adjacency, bendNode, targetNode);
            sw.Stop();

            long memAfter = GC.GetTotalMemory(false);
            long memDeltaKb = Math.Max(0, (memAfter - memBefore) / 1024);

            _output.WriteLine($"=== EVIDENCIA C.1: RENDIMIENTO A* EN GRAFO REAL ===");
            _output.WriteLine($"  Nodos totales en grafo: {graph.Nodes.Count:N0}");
            _output.WriteLine($"  Aristas totales:        {graph.Edges.Count:N0}");
            _output.WriteLine($"  Origen (Bend Node):     {bendNode} (X: {graph.Nodes[(int)bendNode].X:F0}, Z: {graph.Nodes[(int)bendNode].Z:F0})");
            _output.WriteLine($"  Destino (Target Node):  {targetNode} (X: {graph.Nodes[(int)targetNode].X:F0}, Z: {graph.Nodes[(int)targetNode].Z:F0})");
            _output.WriteLine($"  Longitud del camino:    {path?.Count ?? 0} nodos");
            _output.WriteLine($"  Tiempo transcurrido:    {sw.ElapsedMilliseconds} ms ({sw.ElapsedTicks} ticks)");
            _output.WriteLine($"  Memoria aproximada:     {memDeltaKb} KB");

            Assert.True(sw.ElapsedMilliseconds < 500, $"El ruteo A* debe tardar menos de 500ms en el grafo completo. Medido: {sw.ElapsedMilliseconds}ms");
        }

        [Fact]
        public void C2_Prefab_Connectivity_EmpiricalVerification_SampleCurveEndsMeetRoads()
        {
            var graph = LoadFullMapIfAvailable();
            if (graph == null || graph.Nodes.Count == 0) return;

            // Tomar una muestra representativa de 100 aristas
            var sampleEdges = graph.Edges.Take(100).ToList();
            int connectedCount = 0;

            foreach (var edge in sampleEdges)
            {
                if (edge.FromNodeIndex < graph.Nodes.Count && edge.ToNodeIndex < graph.Nodes.Count)
                {
                    var fromNode = graph.Nodes[(int)edge.FromNodeIndex];
                    var toNode = graph.Nodes[(int)edge.ToNodeIndex];

                    // Si la arista tiene polilínea, verificar que el primer punto conecta con fromNode (< 1.0m)
                    // y el último punto conecta con toNode (< 1.0m)
                    if (edge.Polyline != null && edge.Polyline.Count > 0)
                    {
                        var startPt = edge.Polyline.First();
                        var endPt = edge.Polyline.Last();

                        float distStart = Vector3.Distance(new Vector3(fromNode.X, fromNode.Y, fromNode.Z), startPt);
                        float distEnd = Vector3.Distance(new Vector3(toNode.X, toNode.Y, toNode.Z), endPt);

                        if (distStart < 1.0f && distEnd < 1.0f)
                        {
                            connectedCount++;
                        }
                    }
                    else
                    {
                        connectedCount++;
                    }
                }
            }

            double connectivityPercent = (connectedCount / (double)sampleEdges.Count) * 100.0;
            _output.WriteLine($"=== EVIDENCIA C.2: CONECTIVIDAD DE CURVAS A NODOS VECINOS ===");
            _output.WriteLine($"  Muestra analizada:          {sampleEdges.Count} tramos");
            _output.WriteLine($"  Tramos conectados (< 1.0m): {connectedCount}");
            _output.WriteLine($"  Porcentaje de conectividad: {connectivityPercent:F1}%");

            Assert.True(connectivityPercent >= 95.0, $"La conectividad topológica debe ser al menos del 95%. Medido: {connectivityPercent}%");
        }

        [Fact]
        public void C3_ForbiddenTurns_EmpiricalVerification_RespectsIntersectionTopology()
        {
            // Modelo de intersección T con giro a la izquierda prohibido:
            // Entrada Sur (Nodo 0) -> Centro (Nodo 1)
            // Centro (Nodo 1) -> Salida Este (Nodo 2) [Giro a la derecha PERMITIDO]
            // Salida Oeste (Nodo 3) -> Entrada con mediana dividida
            // No existe conector directo de 1 -> 3 (giro a la izquierda prohibido por mediana)

            var graph = new MapGraph();
            graph.Nodes.Add(new MapNode { Index = 0, X = 0, Y = 0, Z = -100 });
            graph.Nodes.Add(new MapNode { Index = 1, X = 0, Y = 0, Z = 0 });
            graph.Nodes.Add(new MapNode { Index = 2, X = 100, Y = 0, Z = 0 });
            graph.Nodes.Add(new MapNode { Index = 3, X = -100, Y = 0, Z = 0 });

            // Aristas permitidas:
            graph.Edges.Add(new MapEdge { EdgeId = 0, FromNodeIndex = 0, ToNodeIndex = 1, Length = 100f, Flags = 1 });
            graph.Edges.Add(new MapEdge { EdgeId = 1, FromNodeIndex = 1, ToNodeIndex = 2, Length = 100f, Flags = 1 });
            // De 3 se puede ir a 1, pero no de 1 a 3 (One-Way / Giro no disponible)
            graph.Edges.Add(new MapEdge { EdgeId = 2, FromNodeIndex = 3, ToNodeIndex = 1, Length = 100f, Flags = 1 });

            var adjacency = BuildAdjacency(graph);

            // Giro permitido: 0 -> 1 -> 2
            var allowedPath = ExecuteAStar(graph, adjacency, 0, 2);
            Assert.NotNull(allowedPath);
            Assert.Equal(new uint[] { 0, 1, 2 }, allowedPath);

            // Giro prohibido: 0 -> 1 -> 3
            var forbiddenPath = ExecuteAStar(graph, adjacency, 0, 3);
            Assert.Null(forbiddenPath);

            _output.WriteLine("=== EVIDENCIA C.3: MANIOBRAS Y GIROS PROHIBIDOS ===");
            _output.WriteLine("  Giro derecha permitido (0 -> 1 -> 2): EXITOSO (Ruta calculada: 0, 1, 2)");
            _output.WriteLine("  Giro izquierda prohibido (0 -> 1 -> 3): RECHAZADO CORRECTAMENTE (Ruta nula)");
        }

        [Fact]
        public void C4_DynamicSectorDiscovery_CoverageTest_DetectsMissingSectors()
        {
            string atsDir = @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
            if (!Directory.Exists(atsDir))
            {
                _output.WriteLine("[OMITIDO] Carpeta oficial de ATS no encontrada en máquina local.");
                return;
            }

            var extractor = new MapExtractor(atsDir);
            var sectors = extractor.DiscoverSectors();

            _output.WriteLine($"=== EVIDENCIA C.4: DESCUBRIMIENTO DINÁMICO DE SECTORES ===");
            _output.WriteLine($"  Sectores descubiertos en usa.mbd: {sectors.Count}");

            Assert.True(sectors.Count > 50, $"Se esperaban más de 50 sectores dinámicos instalados. Encontrados: {sectors.Count}");

            // Verificar que los sectores de Bend, OR están presentes en el descubrimiento
            bool hasBend1 = sectors.Any(s => s.X == -26 && s.Z == -11);
            bool hasBend2 = sectors.Any(s => s.X == -26 && s.Z == -10);

            _output.WriteLine($"  Sector Bend (-26, -11) detectado: {hasBend1}");
            _output.WriteLine($"  Sector Bend (-26, -10) detectado: {hasBend2}");

            Assert.True(hasBend1, "El sector (-26, -11) de Bend, OR debe estar presente.");
            Assert.True(hasBend2, "El sector (-26, -10) de Bend, OR debe estar presente.");
        }

        // ==========================================
        // HELPERS
        // ==========================================

        private static uint FindNearestNode(MapGraph graph, float targetX, float targetZ)
        {
            uint bestNode = 0;
            float bestDistSq = float.MaxValue;

            for (int i = 0; i < graph.Nodes.Count; i++)
            {
                var n = graph.Nodes[i];
                float dx = n.X - targetX;
                float dz = n.Z - targetZ;
                float distSq = dx * dx + dz * dz;
                if (distSq < bestDistSq)
                {
                    bestDistSq = distSq;
                    bestNode = (uint)i;
                }
            }

            return bestNode;
        }

        private static Dictionary<uint, List<MapEdge>> BuildAdjacency(MapGraph graph)
        {
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
            return adjacency;
        }

        private static List<uint>? ExecuteAStar(MapGraph graph, Dictionary<uint, List<MapEdge>> adjacency, uint startNode, uint targetNode)
        {
            var openSet = new PriorityQueue<uint, float>();
            var cameFrom = new Dictionary<uint, uint>();
            var gScore = new Dictionary<uint, float>();

            gScore[startNode] = 0f;
            openSet.Enqueue(startNode, Vector3.Distance(
                new Vector3(graph.Nodes[(int)startNode].X, 0, graph.Nodes[(int)startNode].Z),
                new Vector3(graph.Nodes[(int)targetNode].X, 0, graph.Nodes[(int)targetNode].Z)));

            while (openSet.Count > 0)
            {
                uint current = openSet.Dequeue();
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
    }
}
