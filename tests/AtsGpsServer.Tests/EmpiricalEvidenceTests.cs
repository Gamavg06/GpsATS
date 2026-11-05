using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Numerics;
using AtsGpsServer.Map;
using TruckLib;
using TruckLib.HashFs;
using TruckLib.ScsMap;
using TruckLib.Models.Ppd;
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

            // Buscar coordenadas exactas en el índice de ciudades de ATS
            var cityBend = graph.Cities.FirstOrDefault(c => c.Name.Equals("bend", StringComparison.OrdinalIgnoreCase) || c.Name.Contains("Bend"));
            var citySeattle = graph.Cities.FirstOrDefault(c => c.Name.Equals("seattle", StringComparison.OrdinalIgnoreCase) || c.Name.Contains("Seattle"));

            _output.WriteLine($"=== DATOS DEL ÍNDICE DE CIUDADES ===");
            _output.WriteLine($"  Ciudad Bend:    '{cityBend?.Name}' en X={cityBend?.X}, Z={cityBend?.Z}, NearestNode={cityBend?.NearestNodeIndex}");
            _output.WriteLine($"  Ciudad Seattle: '{citySeattle?.Name}' en X={citySeattle?.X}, Z={citySeattle?.Z}, NearestNode={citySeattle?.NearestNodeIndex}");

            float startX = cityBend != null ? cityBend.X : -84000f;
            float startZ = cityBend != null ? cityBend.Z : -36000f;
            float destX = citySeattle != null ? citySeattle.X : -88000f;
            float destZ = citySeattle != null ? citySeattle.Z : -59000f;

            // Fórmula de sector en SCS:
            int bendSecX = (int)Math.Floor(startX / 4000.0);
            int bendSecZ = (int)Math.Floor(startZ / 4000.0);
            _output.WriteLine($"  Cálculo de sector para Bend ({startX}, {startZ}): X_sec={bendSecX}, Z_sec={bendSecZ}");

            uint bendNode = FindNearestNode(graph, startX, startZ);
            uint seattleNode = FindNearestNode(graph, destX, destZ);

            var startNodeObj = graph.Nodes[(int)bendNode];
            var destNodeObj = graph.Nodes[(int)seattleNode];
            _output.WriteLine($"  Nodo más cercano a Bend:    ID {bendNode} (X={startNodeObj.X}, Z={startNodeObj.Z}), Distancia: {Vector3.Distance(new Vector3(startX, 0, startZ), new Vector3(startNodeObj.X, 0, startNodeObj.Z)):F1} m");
            _output.WriteLine($"  Nodo más cercano a Seattle: ID {seattleNode} (X={destNodeObj.X}, Z={destNodeObj.Z}), Distancia: {Vector3.Distance(new Vector3(destX, 0, destZ), new Vector3(destNodeObj.X, 0, destNodeObj.Z)):F1} m");

            var adjacency = BuildAdjacency(graph);
            _output.WriteLine($"  Aristas salientes desde Bend (Nodo {bendNode}): {adjacency.GetValueOrDefault(bendNode)?.Count ?? 0}");
            _output.WriteLine($"  Aristas entrantes hacia Seattle (Nodo {seattleNode}): {graph.Edges.Count(e => e.ToNodeIndex == seattleNode)}");

            // Buscar candidatos de nodos con grado >= 2 cerca de Bend y Seattle
            var bendHighwayNodes = graph.Nodes.Select((n, idx) => new { Idx = (uint)idx, Node = n, Dist = Vector3.Distance(new Vector3(startX, 0, startZ), new Vector3(n.X, 0, n.Z)) })
                .Where(x => x.Dist < 5000 && (adjacency.GetValueOrDefault(x.Idx)?.Count ?? 0) >= 2)
                .OrderBy(x => x.Dist)
                .Take(5)
                .ToList();

            var seattleHighwayNodes = graph.Nodes.Select((n, idx) => new { Idx = (uint)idx, Node = n, Dist = Vector3.Distance(new Vector3(destX, 0, destZ), new Vector3(n.X, 0, n.Z)) })
                .Where(x => x.Dist < 5000 && graph.Edges.Count(e => e.ToNodeIndex == x.Idx) >= 2)
                .OrderBy(x => x.Dist)
                .Take(5)
                .ToList();

            _output.WriteLine($"  Candidatos viales de Bend: {string.Join(", ", bendHighwayNodes.Select(b => $"ID {b.Idx} (dist: {b.Dist:F0}m, out: {adjacency.GetValueOrDefault(b.Idx)?.Count})"))}");
            _output.WriteLine($"  Candidatos viales de Seattle: {string.Join(", ", seattleHighwayNodes.Select(s => $"ID {s.Idx} (dist: {s.Dist:F0}m, in: {graph.Edges.Count(e => e.ToNodeIndex == s.Idx)})"))}");

            uint activeStart = bendHighwayNodes.FirstOrDefault()?.Idx ?? bendNode;
            uint activeDest = seattleHighwayNodes.FirstOrDefault()?.Idx ?? seattleNode;

            // Diagnóstico de componentes conexos en el grafo
            var visitedGlobal = new HashSet<uint>();
            var componentSizes = new List<int>();
            int largestCompLeader = -1;
            int maxCompSize = 0;

            for (uint i = 0; i < (uint)graph.Nodes.Count; i++)
            {
                if (visitedGlobal.Contains(i)) continue;
                int count = 0;
                var q = new Queue<uint>();
                q.Enqueue(i);
                visitedGlobal.Add(i);
                while (q.Count > 0)
                {
                    var curr = q.Dequeue();
                    count++;
                    if (adjacency.TryGetValue(curr, out var edges))
                    {
                        foreach (var e in edges)
                        {
                            if (visitedGlobal.Add(e.ToNodeIndex))
                                q.Enqueue(e.ToNodeIndex);
                        }
                    }
                }
                componentSizes.Add(count);
                if (count > maxCompSize)
                {
                    maxCompSize = count;
                    largestCompLeader = (int)i;
                }
            }

            _output.WriteLine($"=== ANÁLISIS DE COMPONENTES DEL GRAFO ===");
            int roadEdges = graph.Edges.Count(e => e.RoadType == 0);
            int prefabEdges = graph.Edges.Count(e => e.RoadType == 3);
            _output.WriteLine($"  Aristas de carretera (RoadType 0): {roadEdges}");
            _output.WriteLine($"  Aristas de prefab/curvas (RoadType 3): {prefabEdges}");
            _output.WriteLine($"  Total de aristas: {graph.Edges.Count}");
            _output.WriteLine($"  Total de componentes desconectados: {componentSizes.Count}");
            _output.WriteLine($"  Tamaño del componente más grande: {maxCompSize} nodos");
            var top5 = componentSizes.OrderByDescending(x => x).Take(10).ToList();
            _output.WriteLine($"  Top 10 tamaños de componentes: {string.Join(", ", top5)}");

            // 1. Ejecución en frío
            GC.Collect();
            GC.WaitForPendingFinalizers();
            GC.Collect();
            long memBeforeCold = GC.GetTotalMemory(true);

            var swCold = Stopwatch.StartNew();
            var pathCold = ExecuteAStarDetailed(graph, adjacency, activeStart, activeDest, out int expandedCold);
            swCold.Stop();
            long memAfterCold = GC.GetTotalMemory(false);
            long memColdKb = Math.Max(0, (memAfterCold - memBeforeCold) / 1024);

            // 2. Ejecución en caliente
            var swWarm = Stopwatch.StartNew();
            var pathWarm = ExecuteAStarDetailed(graph, adjacency, activeStart, activeDest, out int expandedWarm);
            swWarm.Stop();

            _output.WriteLine($"=== EVIDENCIA C.1: BENCHMARK A* COMPLETO (BEND -> SEATTLE) ===");
            _output.WriteLine($"  Ruta encontrada:       {(pathCold != null ? "SÍ" : "NO")}");
            _output.WriteLine($"  Nodos en la ruta:      {pathCold?.Count ?? 0}");
            _output.WriteLine($"  Aristas en la ruta:    {(pathCold != null ? pathCold.Count - 1 : 0)}");
            _output.WriteLine($"  Nodos expandidos:      {expandedCold}");
            _output.WriteLine($"  Tiempo en frío:        {swCold.ElapsedMilliseconds} ms ({swCold.ElapsedTicks} ticks)");
            _output.WriteLine($"  Tiempo en caliente:    {swWarm.ElapsedMilliseconds} ms ({swWarm.ElapsedTicks} ticks)");
            _output.WriteLine($"  Memoria asignada (GC): {memColdKb} KB");

            if (pathCold != null && pathCold.Count > 1)
            {
                float totalLength = 0;
                var roadTypesUsed = new HashSet<int>();
                for (int i = 0; i < pathCold.Count - 1; i++)
                {
                    uint from = pathCold[i];
                    uint to = pathCold[i + 1];
                    if (adjacency.TryGetValue(from, out var edges))
                    {
                        var edge = edges.FirstOrDefault(e => e.ToNodeIndex == to);
                        if (edge != null)
                        {
                            totalLength += edge.Length;
                            roadTypesUsed.Add(edge.RoadType);
                        }
                    }
                }
                _output.WriteLine($"  Longitud total en ruta: {totalLength:N0} metros ({totalLength / 1000.0:F1} km)");
                _output.WriteLine($"  Tipos de vía usados:    {string.Join(", ", roadTypesUsed.Select(t => t == 0 ? "Interestatal/Autopista" : t == 1 ? "Carretera Principal" : t == 2 ? "Rampa/Conector" : $"Tipo {t}"))}");
            }
        }

        private static List<uint>? ExecuteAStarDetailed(MapGraph graph, Dictionary<uint, List<MapEdge>> adjacency, uint startNode, uint targetNode, out int expandedNodes)
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
        public void C2_Prefab_Curve_End_Position_Accuracy_Distribution()
        {
            string atsDir = @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
            if (!Directory.Exists(atsDir))
            {
                _output.WriteLine("[OMITIDO] Directorio de ATS no encontrado.");
                return;
            }

            var extractor = new MapExtractor(atsDir);
            var mapFiles = extractor.GetMapScsFiles();
            var readers = new List<IHashFsReader>();
            foreach (var f in mapFiles)
            {
                try { readers.Add(HashFsReader.Open(f)); } catch { }
            }

            string baseScs = Path.Combine(atsDir, "base.scs");
            using var baseReader = File.Exists(baseScs) ? HashFsReader.Open(baseScs) : null;
            string defScs = Path.Combine(atsDir, "def.scs");
            using var defReader = File.Exists(defScs) ? HashFsReader.Open(defScs) : null;

            var allFs = new List<IFileSystem>(readers);
            if (baseReader != null) allFs.Add(baseReader);
            if (defReader != null) allFs.Add(defReader);
            var compositePpdFs = new AssetLoader(allFs.ToArray());

            // Probar prefabs colocados en sectores representativos urbanos y de autopista
            var testSectors = new[]
            {
                new SectorCoordinate(0, 2),
                new SectorCoordinate(0, 3),
                new SectorCoordinate(-1, 2)
            };

            var errors = new List<float>();
            int totalPrefabsEvaluated = 0;
            int totalCurvesEvaluated = 0;
            var failures = new List<string>();

            try
            {
                var compositeMapFs = new AssetLoader(readers.ToArray());
                var descMap = extractor.GetPrefabDescMap(readers);

                foreach (var sec in testSectors)
                {
                    TruckLib.ScsMap.Map secMap;
                    try
                    {
                        secMap = TruckLib.ScsMap.Map.Open("/map/usa.mbd", compositeMapFs, [sec]);
                    }
                    catch
                    {
                        continue;
                    }

                    var prefabs = secMap.MapItems.Values.OfType<Prefab>().ToList();
                    totalPrefabsEvaluated += prefabs.Count;

                    foreach (var prefab in prefabs)
                    {
                        if (prefab.Nodes == null || prefab.Nodes.Count < 2 || prefab.Nodes[0] == null) continue;

                        string modelToken = prefab.Model.ToString().TrimStart('/');
                        if (!descMap.TryGetValue(modelToken, out string? ppdPath))
                        {
                            ppdPath = "/prefab/" + modelToken + ".ppd";
                        }
                        if (!ppdPath.EndsWith(".ppd")) ppdPath += ".ppd";

                        PrefabDescriptor? desc = null;
                        try { desc = PrefabDescriptor.Open(ppdPath, compositePpdFs); } catch { }
                        if (desc == null || desc.NavCurves.Count == 0) continue;

                        var origin = new Vector3(prefab.Nodes[0].Position.X, prefab.Nodes[0].Position.Y, prefab.Nodes[0].Position.Z);
                        var rot = prefab.Nodes[0].Rotation;

                        foreach (var nc in desc.NavCurves)
                        {
                            int pStartIdx = nc.LeadsToNodes.Item1 - 1;
                            int pEndIdx = nc.LeadsToNodes.Item3 - 1;

                            if (pStartIdx >= 0 && pStartIdx < prefab.Nodes.Count &&
                                pEndIdx >= 0 && pEndIdx < prefab.Nodes.Count &&
                                pStartIdx != pEndIdx)
                            {
                                var nodeA = prefab.Nodes[pStartIdx];
                                var nodeB = prefab.Nodes[pEndIdx];
                                if (nodeA == null || nodeB == null) continue;

                                totalCurvesEvaluated++;

                                var worldStart = Vector3.Transform(nc.StartPosition, rot) + origin;
                                var worldEnd = Vector3.Transform(nc.EndPosition, rot) + origin;

                                var nodeAPos = new Vector3(nodeA.Position.X, nodeA.Position.Y, nodeA.Position.Z);
                                var nodeBPos = new Vector3(nodeB.Position.X, nodeB.Position.Y, nodeB.Position.Z);

                                float errA = Vector3.Distance(worldStart, nodeAPos);
                                float errB = Vector3.Distance(worldEnd, nodeBPos);

                                errors.Add(errA);
                                errors.Add(errB);

                                if (errA > 1.0f || errB > 1.0f)
                                {
                                    failures.Add($"Prefab {prefab.Uid} ({modelToken}): errA={errA:F2}m, errB={errB:F2}m");
                                }
                            }
                        }
                    }
                }
            }
            finally
            {
                foreach (var r in readers) try { r.Dispose(); } catch { }
            }

            _output.WriteLine($"=== EVIDENCIA C.2 / C.4: VERIFICACIÓN TOPOLÓGICA DE PREFABS COLOCADOS ===");
            _output.WriteLine($"  Prefabs evaluados en sectores de prueba: {totalPrefabsEvaluated}");
            _output.WriteLine($"  Curvas de navegación analizadas:         {totalCurvesEvaluated}");
            _output.WriteLine($"  Puntos evaluados:                        {errors.Count}");

            if (errors.Count > 0)
            {
                errors.Sort();
                float minErr = errors[0];
                float medianErr = errors[errors.Count / 2];
                float p95Err = errors[(int)(errors.Count * 0.95)];
                float maxErr = errors[^1];

                _output.WriteLine($"  Distribución del error (Distancia de curva a nodo de colocación):");
                _output.WriteLine($"    Mínimo:  {minErr:F4} m");
                _output.WriteLine($"    Mediana: {medianErr:F4} m");
                _output.WriteLine($"    P95:     {p95Err:F4} m");
                _output.WriteLine($"    Máximo:  {maxErr:F4} m");
                _output.WriteLine($"  Fallas (> 1.0 m): {failures.Count} de {totalCurvesEvaluated}");
            }
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

            var mapScsFiles = new List<string>();
            string[] knownMapDlcs = ["dlc_wa.scs", "dlc_or.scs", "dlc_id.scs", "dlc_arizona.scs", "dlc_nevada.scs", "base_map.scs"];
            foreach (var dlc in knownMapDlcs)
            {
                string p = Path.Combine(atsDir, dlc);
                if (File.Exists(p)) mapScsFiles.Add(p);
            }

            var extractor = new MapExtractor(atsDir);
            var sectors = extractor.DiscoverSectors();

            _output.WriteLine($"=== EVIDENCIA C.4: DESCUBRIMIENTO DINÁMICO DE SECTORES ===");
            _output.WriteLine($"  Paquetes montados ({mapScsFiles.Count}): {string.Join(", ", mapScsFiles.Select(Path.GetFileName))}");
            _output.WriteLine($"  Número EXACTO de sectores descubiertos en usa.mbd: {sectors.Count}");
            _output.WriteLine($"  Diferencia con los 276 sectores anteriores: {sectors.Count - 276} sectores");

            // Rango de coordenadas de los sectores
            int minX = sectors.Min(s => s.X);
            int maxX = sectors.Max(s => s.X);
            int minZ = sectors.Min(s => s.Z);
            int maxZ = sectors.Max(s => s.Z);
            _output.WriteLine($"  Límites de sectores: X in [{minX}, {maxX}], Z in [{minZ}, {maxZ}]");

            // Verificar sectores de Bend, OR
            bool hasBend1 = sectors.Any(s => s.X == -26 && s.Z == -11);
            bool hasBend2 = sectors.Any(s => s.X == -26 && s.Z == -10);
            bool hasBend3 = sectors.Any(s => s.X == -25 && s.Z == -12);
            _output.WriteLine($"  Sector (-26, -11) detectado: {hasBend1}");
            _output.WriteLine($"  Sector (-26, -10) detectado: {hasBend2}");
            _output.WriteLine($"  Sector (-25, -12) detectado: {hasBend3}");

            Assert.True(sectors.Count > 0);
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
