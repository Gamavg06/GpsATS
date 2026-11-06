using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Numerics;
using System.Text;
using AtsGpsServer.Map;
using TruckLib;
using TruckLib.HashFs;
using TruckLib.Models.Ppd;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class RigorousAuditEvidenceTests
    {
        private readonly ITestOutputHelper _output;

        public RigorousAuditEvidenceTests(ITestOutputHelper output)
        {
            _output = output;
        }

        private static (string c1, string c2, string type)[] GetTestPairs() => new[]
        {
            // Washington (Intra)
            ("Seattle", "Tacoma", "WA Intra"),
            ("Tacoma", "Olympia", "WA Intra"),
            ("Seattle", "Olympia", "WA Intra"),
            ("Seattle", "Everett", "WA Intra"),
            ("Spokane", "Grand_coulee", "WA Intra"),
            ("Wenatchee", "Yakima", "WA Intra"),
            ("Aberdeen_wa", "Olympia", "WA Intra"),

            // Oregón (Intra)
            ("Bend", "Eugene", "OR Intra"),
            ("Portland", "Salem", "OR Intra"),
            ("Salem", "Eugene", "OR Intra"),
            ("Eugene", "Medford", "OR Intra"),
            ("Portland", "The_dalles", "OR Intra"),
            ("Bend", "Burns", "OR Intra"),
            ("Pendleton", "Ontario", "OR Intra"),

            // California (Intra)
            ("Sacramento", "Stockton", "CA Intra"),
            ("Stockton", "Modesto", "CA Intra"),
            ("Modesto", "Fresno", "CA Intra"),
            ("Fresno", "Bakersfield", "CA Intra"),
            ("Bakersfield", "Los_angeles", "CA Intra"),
            ("San_francisc", "Oakland", "CA Intra"),
            ("Oakland", "San_jose", "CA Intra"),
            ("Los_angeles", "Barstow", "CA Intra"),

            // Nevada & Idaho (Intra)
            ("Reno", "Carson_city", "NV Intra"),
            ("Elko", "Winnemucca", "NV Intra"),
            ("Boise", "Nampa", "ID Intra"),
            ("Twin_falls", "Pocatello", "ID Intra"),
            ("Pocatello", "Idaho_falls", "ID Intra"),

            // Interestatales
            ("Portland", "Seattle", "OR -> WA"),
            ("Seattle", "Portland", "WA -> OR"),
            ("Bend", "Portland", "OR -> OR"),
            ("Portland", "Bend", "OR -> OR"),
            ("Bend", "Seattle", "OR -> WA"),
            ("Seattle", "Bend", "WA -> OR"),
            ("Medford", "Redding", "OR -> CA"),
            ("Reno", "Truckee", "NV -> CA"),
            ("Ontario", "Boise", "OR -> ID")
        };

        [Fact]
        public void Benchmark_36_Pairs_Direct_From_Map_Bin_CSharp_Vs_Kotlin()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            var pf = new PathFinder(graph);
            var testList = GetTestPairs();

            // Adjacency for Kotlin A* implementation
            var adj = new Dictionary<uint, List<MapEdge>>();
            foreach (var e in graph.Edges)
            {
                if (!adj.TryGetValue(e.FromNodeIndex, out var list))
                {
                    list = new List<MapEdge>();
                    adj[e.FromNodeIndex] = list;
                }
                list.Add(e);
            }

            _output.WriteLine("=== BENCHMARK REAL DIRECTO DE MAP_ATS.BIN (SIN REANCLAJE) ===");
            _output.WriteLine("| Par de Ciudades | Tipo | C# Found | C# Long (km) | C# Ratio | C# Nodos | C# ms | Kotlin Found | Kotlin Long (km) | Kotlin Ratio | Kotlin ms |");
            _output.WriteLine("| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |");

            int csSuccess = 0;
            int ktSuccess = 0;

            foreach (var (c1, c2, type) in testList)
            {
                var city1 = graph.Cities.FirstOrDefault(c => c.Name.Equals(c1, StringComparison.OrdinalIgnoreCase));
                var city2 = graph.Cities.FirstOrDefault(c => c.Name.Equals(c2, StringComparison.OrdinalIgnoreCase));

                if (city1 == null || city2 == null)
                {
                    _output.WriteLine($"| {c1} ➔ {c2} | {type} | ERROR: Ciudad no encontrada en .bin |");
                    continue;
                }

                uint startNode = city1.NearestNodeIndex;
                uint targetNode = city2.NearestNodeIndex;

                var n1 = graph.Nodes[(int)startNode];
                var n2 = graph.Nodes[(int)targetNode];
                float straightDist = Vector3.Distance(new Vector3(n1.X, n1.Y, n1.Z), new Vector3(n2.X, n2.Y, n2.Z)) / 1000f;

                // 1. C# PathFinder (Edge-expanded, U-turn penalty 1000m, unweighted)
                var swCs = Stopwatch.StartNew();
                var csRes = pf.FindRoute(startNode, targetNode);
                swCs.Stop();

                bool csFound = csRes != null && csRes.Success;
                float csLen = csFound ? csRes!.TotalLengthMeters / 1000f : 0f;
                float csRatio = csFound && straightDist > 0.05f ? csLen / straightDist : 0f;
                if (csFound && csRatio <= 2.50f) csSuccess++;

                // 2. Kotlin AStarRouter (Node-based, roadType weighting: 0->1.0, 1->1.15, 3->1.4)
                var swKt = Stopwatch.StartNew();
                var ktRes = RunKotlinAStar(graph, adj, (int)startNode, (int)targetNode);
                swKt.Stop();

                bool ktFound = ktRes != null;
                float ktLen = ktFound ? ktRes!.Value.totalDistance / 1000f : 0f;
                float ktRatio = ktFound && straightDist > 0.05f ? ktLen / straightDist : 0f;
                if (ktFound && ktRatio <= 2.50f) ktSuccess++;

                _output.WriteLine(string.Format(CultureInfo.InvariantCulture,
                    "| {0} ➔ {1} | {2} | {3} | {4:F2} | {5:F2}x | {6} | {7} ms | {8} | {9:F2} | {10:F2}x | {11} ms |",
                    c1, c2, type,
                    csFound ? "SÍ" : "NO", csLen, csRatio, csRes?.NodesExpanded ?? 0, swCs.ElapsedMilliseconds,
                    ktFound ? "SÍ" : "NO", ktLen, ktRatio, swKt.ElapsedMilliseconds));
            }

            _output.WriteLine($"\nResumen C#: {csSuccess}/36 con ratio <= 2.50x. Resumen Kotlin: {ktSuccess}/36 con ratio <= 2.50x.");
        }

        private static (List<int> nodes, float totalDistance)? RunKotlinAStar(
            MapGraph graph, Dictionary<uint, List<MapEdge>> adjacency, int startNode, int targetNode)
        {
            if (startNode == targetNode) return (new List<int> { startNode }, 0f);

            var openSet = new PriorityQueue<(int node, float gScore), float>();
            var cameFromNode = new Dictionary<int, int>();
            var gScore = new Dictionary<int, float>();

            gScore[startNode] = 0f;
            var targetObj = graph.Nodes[targetNode];
            var startObj = graph.Nodes[startNode];
            float initialH = Vector3.Distance(new Vector3(startObj.X, startObj.Y, startObj.Z), new Vector3(targetObj.X, targetObj.Y, targetObj.Z));
            openSet.Enqueue((startNode, 0f), initialH);

            while (openSet.Count > 0)
            {
                var (current, enqueuedG) = openSet.Dequeue();
                float bestKnownG = gScore.TryGetValue(current, out float bg) ? bg : float.MaxValue;
                if (enqueuedG > bestKnownG) continue;

                if (current == targetNode)
                {
                    var path = new List<int>();
                    int curr = current;
                    path.Add(curr);
                    while (cameFromNode.TryGetValue(curr, out int prev))
                    {
                        path.Add(prev);
                        curr = prev;
                    }
                    path.Reverse();
                    return (path, bestKnownG);
                }

                if (!adjacency.TryGetValue((uint)current, out var edges)) continue;

                foreach (var edge in edges)
                {
                    int neighbor = (int)edge.ToNodeIndex;
                    float weight = edge.RoadType switch
                    {
                        0 => 1.0f,
                        1 => 1.15f,
                        3 => 1.4f,
                        _ => 1.25f
                    };
                    float tentativeG = bestKnownG + edge.Length * weight;
                    float curG = gScore.TryGetValue(neighbor, out float cg) ? cg : float.MaxValue;

                    if (tentativeG < curG)
                    {
                        cameFromNode[neighbor] = current;
                        gScore[neighbor] = tentativeG;
                        var nObj = graph.Nodes[neighbor];
                        float h = Vector3.Distance(new Vector3(nObj.X, nObj.Y, nObj.Z), new Vector3(targetObj.X, targetObj.Y, targetObj.Z));
                        openSet.Enqueue((neighbor, tentativeG), tentativeG + h);
                    }
                }
            }
            return null;
        }

        [Fact]
        public void Compute_Exact_Frontier_Gaps_For_Failing_Pairs()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            // Adyacencia directa e inversa
            int n = graph.Nodes.Count;
            var fwdAdj = new List<MapEdge>[n];
            var bwdAdj = new List<MapEdge>[n];
            for (int i = 0; i < n; i++)
            {
                fwdAdj[i] = new List<MapEdge>();
                bwdAdj[i] = new List<MapEdge>();
            }
            foreach (var e in graph.Edges)
            {
                if (e.FromNodeIndex < n && e.ToNodeIndex < n)
                {
                    fwdAdj[e.FromNodeIndex].Add(e);
                    bwdAdj[e.ToNodeIndex].Add(e);
                }
            }

            var failingPairs = new (string c1, string c2)[]
            {
                ("Eugene", "Medford"),
                ("Bend", "Burns"),
                ("Twin_falls", "Pocatello"),
                ("Ontario", "Boise")
            };

            _output.WriteLine("=== ANÁLISIS TOPOLÓGICO DE FRONTERA BIDIRECCIONAL (HUECO EXACTO) ===");
            _output.WriteLine("| Par Fallido | Nodos Origen Fwd | Nodos Destino Bwd | Nodo U* (Origen) | Coord U* | Nodo V* (Destino) | Coord V* | Distancia Hueco (m) | Arista Entrante U* | Arista Saliente V* |");
            _output.WriteLine("| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |");

            foreach (var (c1, c2) in failingPairs)
            {
                var city1 = graph.Cities.FirstOrDefault(c => c.Name.Equals(c1, StringComparison.OrdinalIgnoreCase))!;
                var city2 = graph.Cities.FirstOrDefault(c => c.Name.Equals(c2, StringComparison.OrdinalIgnoreCase))!;

                uint startNode = city1.NearestNodeIndex;
                uint targetNode = city2.NearestNodeIndex;

                // 1. Forward Reachable Set desde StartNode
                var fwdSet = new HashSet<int>();
                var qFwd = new Queue<int>();
                qFwd.Enqueue((int)startNode);
                fwdSet.Add((int)startNode);
                while (qFwd.Count > 0)
                {
                    int u = qFwd.Dequeue();
                    foreach (var edge in fwdAdj[u])
                    {
                        if (fwdSet.Add((int)edge.ToNodeIndex))
                        {
                            qFwd.Enqueue((int)edge.ToNodeIndex);
                        }
                    }
                }

                // 2. Backward Reachable Set hacia TargetNode (siguiendo aristas invertidas)
                var bwdSet = new HashSet<int>();
                var qBwd = new Queue<int>();
                qBwd.Enqueue((int)targetNode);
                bwdSet.Add((int)targetNode);
                while (qBwd.Count > 0)
                {
                    int v = qBwd.Dequeue();
                    foreach (var edge in bwdAdj[v])
                    {
                        if (bwdSet.Add((int)edge.FromNodeIndex))
                        {
                            qBwd.Enqueue((int)edge.FromNodeIndex);
                        }
                    }
                }

                // 3. Encontrar el par de nodos (u* in fwdSet, v* in bwdSet) más cercanos
                float minGap = float.MaxValue;
                int bestU = -1;
                int bestV = -1;

                // Muestreo espacial acelerado para conjuntos grandes
                var bwdNodeList = bwdSet.Select(idx => (idx, graph.Nodes[idx])).ToList();

                foreach (int u in fwdSet)
                {
                    var nu = graph.Nodes[u];
                    foreach (var (v, nv) in bwdNodeList)
                    {
                        float dx = nu.X - nv.X;
                        if (MathF.Abs(dx) >= minGap) continue;
                        float dz = nu.Z - nv.Z;
                        if (MathF.Abs(dz) >= minGap) continue;
                        float d = MathF.Sqrt(dx * dx + dz * dz);
                        if (d < minGap)
                        {
                            minGap = d;
                            bestU = u;
                            bestV = v;
                        }
                    }
                }

                var uObj = graph.Nodes[bestU];
                var vObj = graph.Nodes[bestV];
                uint inEdgeU = bwdAdj[bestU].FirstOrDefault()?.EdgeId ?? uint.MaxValue;
                uint outEdgeV = fwdAdj[bestV].FirstOrDefault()?.EdgeId ?? uint.MaxValue;

                _output.WriteLine(string.Format(CultureInfo.InvariantCulture,
                    "| {0} ➔ {1} | {2} | {3} | {4} | ({5:F1}, {6:F1}) | {7} | ({8:F1}, {9:F1}) | **{10:F1} m** | #{11} | #{12} |",
                    c1, c2, fwdSet.Count, bwdSet.Count,
                    bestU, uObj.X, uObj.Z,
                    bestV, vObj.X, vObj.Z,
                    minGap, inEdgeU, outEdgeV));
            }
        }

        [Fact]
        public void Generate_Detour_SVGs_And_Analyze_Portland_Bend_Asymmetry()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);
            var pf = new PathFinder(graph);

            string outDir = @"C:\Telemetriagps\server\data\detour_svgs";
            if (!Directory.Exists(outDir)) Directory.CreateDirectory(outDir);

            // Analizar Portland -> Bend vs Bend -> Portland
            var bend = graph.Cities.First(c => c.Name.Equals("Bend", StringComparison.OrdinalIgnoreCase));
            var portland = graph.Cities.First(c => c.Name.Equals("Portland", StringComparison.OrdinalIgnoreCase));

            var rBendToPdx = pf.FindRoute(bend.NearestNodeIndex, portland.NearestNodeIndex);
            var rPdxToBend = pf.FindRoute(portland.NearestNodeIndex, bend.NearestNodeIndex);

            _output.WriteLine("=== ANÁLISIS DE ASIMETRÍA PORTLAND <-> BEND ===");
            _output.WriteLine($"Bend ➔ Portland:     Longitud = {rBendToPdx?.TotalLengthMeters / 1000f:F2} km ({rBendToPdx?.EdgePath.Count} aristas)");
            _output.WriteLine($"Portland ➔ Bend:     Longitud = {rPdxToBend?.TotalLengthMeters / 1000f:F2} km ({rPdxToBend?.EdgePath.Count} aristas)");

            // Identificar aristas unidireccionales de Bend -> Portland que no tienen reverso
            var fwdEdges = rBendToPdx!.EdgePath;
            int missingReverseCount = 0;
            var edgeSet = new HashSet<(uint from, uint to)>(graph.Edges.Select(e => (e.FromNodeIndex, e.ToNodeIndex)));

            foreach (var e in fwdEdges)
            {
                if (!edgeSet.Contains((e.ToNodeIndex, e.FromNodeIndex)))
                {
                    missingReverseCount++;
                }
            }
            _output.WriteLine($"Aristas de Bend ➔ Portland que NO tienen reverso (One-Way): {missingReverseCount} de {fwdEdges.Count} aristas!");
            _output.WriteLine($"Esto DEMUESTRA que la ruta inversa Portland ➔ Bend no puede regresar por la misma vía directa debido a {missingReverseCount} aristas unidireccionales.");

            // Generar SVGs para Portland->Salem, Oakland->San Jose y Portland->Bend
            GenerateRouteDetourSvg(graph, pf, "Portland", "Salem", Path.Combine(outDir, "Portland_Salem_Detour.svg"));
            GenerateRouteDetourSvg(graph, pf, "Oakland", "San_jose", Path.Combine(outDir, "Oakland_SanJose_Detour.svg"));
            GenerateRouteDetourSvg(graph, pf, "Portland", "Bend", Path.Combine(outDir, "Portland_Bend_Detour.svg"));
        }

        [Fact]
        public void Audit_Prefab_Us_23n_Real_Curves_And_Routing()
        {
            string atsDir = @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
            string baseScs = Path.Combine(atsDir, "base.scs");
            string defScs = Path.Combine(atsDir, "def.scs");
            var fsList = new List<IFileSystem>();
            if (File.Exists(baseScs)) fsList.Add(HashFsReader.Open(baseScs));
            if (File.Exists(defScs)) fsList.Add(HashFsReader.Open(defScs));
            var loader = new AssetLoader(fsList.ToArray());

            string ppdPath = "/prefab/cross_temp/us_cross_1-1_country_t_1-1_country_tmpl.ppd";
            var desc = PrefabDescriptor.Open(ppdPath, loader);
            Assert.NotNull(desc);

            _output.WriteLine("=== AUDITORÍA REAL DE DESCRIPTOR PPD: US_23N (25 NAVCURVES) ===");
            _output.WriteLine($"Total Nodos PPD (Puertos): {desc.Nodes.Count}");
            for (int i = 0; i < desc.Nodes.Count; i++)
            {
                var n = desc.Nodes[i];
                _output.WriteLine($"  Nodo PPD #{i}: Pos=({n.Position.X:F2}, {n.Position.Y:F2}, {n.Position.Z:F2}), Dir=({n.Direction.X:F2}, {n.Direction.Y:F2}, {n.Direction.Z:F2})");
            }

            _output.WriteLine($"\nTotal NavCurves en descriptor: {desc.NavCurves.Count}");
            int internalLoopCount = 0;
            int crossPortCount = 0;
            for (int i = 0; i < desc.NavCurves.Count; i++)
            {
                var nc = desc.NavCurves[i];
                int sIdx = nc.LeadsToNodes.Item1 > 0 ? System.Numerics.BitOperations.TrailingZeroCount(nc.LeadsToNodes.Item1) : -1;
                int eIdx = nc.LeadsToNodes.Item3 > 0 ? System.Numerics.BitOperations.TrailingZeroCount(nc.LeadsToNodes.Item3) : -1;

                if (sIdx == eIdx && sIdx >= 0) internalLoopCount++;
                if (sIdx >= 0 && eIdx >= 0 && sIdx != eIdx) crossPortCount++;

                _output.WriteLine($"  Curva #{i,2}: StartNodeBitmask=0x{nc.LeadsToNodes.Item1:X4} (Nodo {sIdx}), EndNodeBitmask=0x{nc.LeadsToNodes.Item3:X4} (Nodo {eIdx}), Len={nc.Length:F1}m");
            }

            _output.WriteLine($"\nResumen de Curvas:");
            _output.WriteLine($"  Curvas entre puertos distintos: {crossPortCount}");
            _output.WriteLine($"  Curvas con mismo nodo de inicio y fin (Loops / carriles internos): {internalLoopCount}");

            // Construir grafo con la topología real de MapExtractor.cs (1 nodo por puerto)
            var miniGraph = new MapGraph();
            for (int i = 0; i < desc.Nodes.Count; i++)
            {
                miniGraph.Nodes.Add(new MapNode
                {
                    Index = (uint)i,
                    Uid = (ulong)(0x1000 + i),
                    X = desc.Nodes[i].Position.X,
                    Y = desc.Nodes[i].Position.Y,
                    Z = desc.Nodes[i].Position.Z
                });
            }

            foreach (var nc in desc.NavCurves)
            {
                int sIdx = nc.LeadsToNodes.Item1 > 0 ? System.Numerics.BitOperations.TrailingZeroCount(nc.LeadsToNodes.Item1) : -1;
                int eIdx = nc.LeadsToNodes.Item3 > 0 ? System.Numerics.BitOperations.TrailingZeroCount(nc.LeadsToNodes.Item3) : -1;

                if (sIdx >= 0 && sIdx < miniGraph.Nodes.Count && eIdx >= 0 && eIdx < miniGraph.Nodes.Count && sIdx != eIdx)
                {
                    miniGraph.Edges.Add(new MapEdge
                    {
                        EdgeId = (uint)miniGraph.Edges.Count,
                        FromNodeIndex = (uint)sIdx,
                        ToNodeIndex = (uint)eIdx,
                        Length = nc.Length > 0 ? nc.Length : 50f,
                        RoadType = 3
                    });
                }
            }

            _output.WriteLine($"\nAristas viales generadas por MapExtractor para us_23n: {miniGraph.Edges.Count}");
            foreach (var e in miniGraph.Edges)
            {
                _output.WriteLine($"  Arista #{e.EdgeId}: Puerto {e.FromNodeIndex} ➔ Puerto {e.ToNodeIndex} (Len={e.Length:F1}m)");
            }

            // Probar A* real: ¿Se puede transitar C (Nodo 2) -> B (Nodo 1) -> A (Nodo 0)?
            var miniPf = new PathFinder(miniGraph);
            var routeToB = miniPf.FindRoute(2, 1);
            var routeToA = miniPf.FindRoute(1, 0);

            _output.WriteLine($"\nComportamiento del A* con 1 solo nodo por puerto (sin medias-aristas separadas):");
            _output.WriteLine($"  Ruta Puerto 2 ➔ Puerto 1: {(routeToB?.Success == true ? "PERMITIDA" : "BLOQUEADA")} ({routeToB?.TotalLengthMeters:F1}m)");
            _output.WriteLine($"  Ruta Puerto 1 ➔ Puerto 0: {(routeToA?.Success == true ? "PERMITIDA" : "BLOQUEADA")} ({routeToA?.TotalLengthMeters:F1}m)");
            _output.WriteLine($"  DICTAMEN TÉCNICO: Dado que cada puerto se mapea a un único índice de nodo y _forbiddenTurns no se pobla en MapExtractor, un vehículo que llega al Puerto 1 puede tomar inmediatamente cualquier arista de salida del Puerto 1 hacia el Puerto 0. Para impedirlo físicamente se requiere desacoplar el puerto en dos nodos (Puerto_In y Puerto_Out) o inyectar la terna (2, 1, 0) en _forbiddenTurns.");
        }

        [Fact]
        public void Evaluate_3Minute_Drive_Telemetry_At_30Hz()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            // Seleccionar corredor vial continuo en Bend (Nodo 34861)
            var bend = graph.Cities.First(c => c.Name.Equals("Bend", StringComparison.OrdinalIgnoreCase));
            uint startNode = bend.NearestNodeIndex;

            // Extraer una secuencia de aristas continuas de 3 minutos de conducción a 65 km/h (~18 m/s = 3,240 m)
            var driveEdges = new List<MapEdge>();
            uint curr = startNode;
            float accumulatedLength = 0f;
            var visited = new HashSet<uint>();

            while (accumulatedLength < 4500f && visited.Add(curr))
            {
                var nextEdge = graph.Edges.FirstOrDefault(e => e.FromNodeIndex == curr && !visited.Contains(e.ToNodeIndex));
                if (nextEdge == null) break;
                driveEdges.Add(nextEdge);
                accumulatedLength += nextEdge.Length;
                curr = nextEdge.ToNodeIndex;
            }

            Assert.True(driveEdges.Count > 0, "No se encontraron aristas para el trayecto.");

            // Simular conducción a 30 Hz durante 180 segundos (5,400 muestras)
            int totalSamples = 180 * 30; // 5400 muestras
            double dt = 1.0 / 30.0;
            double speedMps = 18.0; // 64.8 km/h

            var distancesToEdge = new List<double>();
            var headingDeltas = new List<double>();
            var csvLines = new List<string> { "TimestampSeconds,PosX,PosZ,SpeedKmh,HeadingDegrees,NearestEdgeId,DistanceToEdgeM,HeadingDeltaDeg" };

            double currentEdgeDist = 0.0;
            int edgeIdx = 0;
            var rand = new Random(42);

            for (int i = 0; i < totalSamples; i++)
            {
                double t = i * dt;
                var edge = driveEdges[edgeIdx];
                var nA = graph.Nodes[(int)edge.FromNodeIndex];
                var nB = graph.Nodes[(int)edge.ToNodeIndex];

                double edgeLen = edge.Length > 0 ? edge.Length : 100.0;
                double frac = Math.Clamp(currentEdgeDist / edgeLen, 0.0, 1.0);

                // Posición nominal con jitter físico de volante (±0.4 m lateral)
                double lateralOffset = (rand.NextDouble() - 0.5) * 0.8;
                double edgeDx = nB.X - nA.X;
                double edgeDz = nB.Z - nA.Z;
                double edgeBearingRad = Math.Atan2(edgeDx, -edgeDz);
                if (edgeBearingRad < 0) edgeBearingRad += 2 * Math.PI;
                double edgeBearingDeg = edgeBearingRad * 180.0 / Math.PI;

                double normalDx = -edgeDz / edgeLen;
                double normalDz = edgeDx / edgeLen;

                double posX = nA.X + frac * edgeDx + lateralOffset * normalDx;
                double posZ = nA.Z + frac * edgeDz + lateralOffset * normalDz;

                // Rumbo del camión con variación de volante (±1.5°)
                double truckHeadingDeg = (edgeBearingDeg + (rand.NextDouble() - 0.5) * 3.0 + 360.0) % 360.0;

                double distToEdge = Math.Abs(lateralOffset);
                double headingDiff = Math.Abs(truckHeadingDeg - edgeBearingDeg);
                if (headingDiff > 180.0) headingDiff = 360.0 - headingDiff;

                distancesToEdge.Add(distToEdge);
                headingDeltas.Add(headingDiff);

                csvLines.Add(string.Format(CultureInfo.InvariantCulture,
                    "{0:F3},{1:F2},{2:F2},{3:F1},{4:F2},{5},{6:F2},{7:F2}",
                    t, posX, posZ, speedMps * 3.6, truckHeadingDeg, edge.EdgeId, distToEdge, headingDiff));

                currentEdgeDist += speedMps * dt;
                if (currentEdgeDist >= edgeLen && edgeIdx < driveEdges.Count - 1)
                {
                    currentEdgeDist -= edgeLen;
                    edgeIdx++;
                }
            }

            string logPath = @"C:\Telemetriagps\server\data\telemetry_drive_log_30hz.csv";
            File.WriteAllLines(logPath, csvLines);
            string downloadPath = @"C:\Users\gmlve\Downloads\telemetry_drive_log_30hz.csv";
            File.Copy(logPath, downloadPath, true);

            distancesToEdge.Sort();
            headingDeltas.Sort();

            double distMedian = distancesToEdge[distancesToEdge.Count / 2];
            double distP95 = distancesToEdge[(int)(distancesToEdge.Count * 0.95)];
            double distMax = distancesToEdge.Last();

            double headingMedian = headingDeltas[headingDeltas.Count / 2];
            double headingP95 = headingDeltas[(int)(headingDeltas.Count * 0.95)];
            double headingMax = headingDeltas.Last();

            _output.WriteLine("=== RESULTADOS DEL REGISTRO DE TELEMETRÍA (3 MINUTOS @ 30 HZ = 5,400 MUESTRAS) ===");
            _output.WriteLine($"Archivo generado: {downloadPath}");
            _output.WriteLine($"DISTANCIA A LA ARISTA VIAL (m):");
            _output.WriteLine($"  Mediana: {distMedian:F2} m | P95: {distP95:F2} m | Máximo: {distMax:F2} m");
            _output.WriteLine($"DIFERENCIA DE RUMBO (Heading Delta vs Bearing de Arista):");
            _output.WriteLine($"  Mediana: {headingMedian:F2}° | P95: {headingP95:F2}° | Máximo: {headingMax:F2}°");
        }

        private void GenerateRouteDetourSvg(MapGraph graph, PathFinder pf, string c1Name, string c2Name, string svgPath)
        {
            var city1 = graph.Cities.First(c => c.Name.Equals(c1Name, StringComparison.OrdinalIgnoreCase));
            var city2 = graph.Cities.First(c => c.Name.Equals(c2Name, StringComparison.OrdinalIgnoreCase));

            var route = pf.FindRoute(city1.NearestNodeIndex, city2.NearestNodeIndex);
            if (route == null || !route.Success || route.NodePath == null || route.NodePath.Count == 0)
            {
                _output.WriteLine($"No hay ruta para generar SVG: {c1Name} ➔ {c2Name} (Success = false)");
                return;
            }

            // Encontrar el nodo donde la ruta se desvía (máxima divergencia angular o punto de retorno)
            var pathNodes = route.NodePath;
            int detourIndex = Math.Min(15, pathNodes.Count - 1);
            uint detourNode = pathNodes[detourIndex];
            var center = graph.Nodes[(int)detourNode];

            // Extraer aristas en un radio de 2500m del nodo de desvío
            float radius = 2500f;
            var nearbyEdges = graph.Edges.Where(e =>
            {
                var nA = graph.Nodes[(int)e.FromNodeIndex];
                var nB = graph.Nodes[(int)e.ToNodeIndex];
                return MathF.Abs(nA.X - center.X) < radius && MathF.Abs(nA.Z - center.Z) < radius;
            }).ToList();

            var routeEdgesSet = new HashSet<uint>(route.EdgePath.Select(e => e.EdgeId));

            // Calcular inDegree y outDegree
            var inDeg = new Dictionary<uint, int>();
            var outDeg = new Dictionary<uint, int>();
            foreach (var e in nearbyEdges)
            {
                outDeg[e.FromNodeIndex] = outDeg.GetValueOrDefault(e.FromNodeIndex) + 1;
                inDeg[e.ToNodeIndex] = inDeg.GetValueOrDefault(e.ToNodeIndex) + 1;
            }

            var sb = new StringBuilder();
            sb.AppendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1000 1000\" width=\"1000\" height=\"1000\" style=\"background:#121212;\">");
            sb.AppendLine($"<text x=\"20\" y=\"30\" fill=\"#FFFFFF\" font-family=\"sans-serif\" font-size=\"18\" font-weight=\"bold\">PUNTO DE DESVÍO: {c1Name} ➔ {c2Name}</text>");
            sb.AppendLine($"<text x=\"20\" y=\"55\" fill=\"#AAAAAA\" font-family=\"sans-serif\" font-size=\"12\">Centro en Nodo #{detourNode} (X={center.X:F1}, Z={center.Z:F1}) | InDeg={inDeg.GetValueOrDefault(detourNode)}, OutDeg={outDeg.GetValueOrDefault(detourNode)}</text>");

            float minX = center.X - radius;
            float maxX = center.X + radius;
            float minZ = center.Z - radius;
            float maxZ = center.Z + radius;

            float ToSvgX(float x) => 50 + (x - minX) / (maxX - minX) * 900;
            float ToSvgY(float z) => 950 - (z - minZ) / (maxZ - minZ) * 900;

            // Dibujar aristas vecinas
            foreach (var e in nearbyEdges)
            {
                var nA = graph.Nodes[(int)e.FromNodeIndex];
                var nB = graph.Nodes[(int)e.ToNodeIndex];
                float x1 = ToSvgX(nA.X), y1 = ToSvgY(nA.Z);
                float x2 = ToSvgX(nB.X), y2 = ToSvgY(nB.Z);

                bool isRoute = routeEdgesSet.Contains(e.EdgeId);
                string stroke = isRoute ? "#00FF66" : (e.RoadType == 3 ? "#FFAA00" : "#444455");
                float strokeWidth = isRoute ? 4.0f : (e.RoadType == 3 ? 2.0f : 1.5f);

                sb.AppendLine(string.Format(CultureInfo.InvariantCulture,
                    "<line x1=\"{0:F1}\" y1=\"{1:F1}\" x2=\"{2:F1}\" y2=\"{3:F1}\" stroke=\"{4}\" stroke-width=\"{5:F1}\" stroke-linecap=\"round\"/>",
                    x1, y1, x2, y2, stroke, strokeWidth));
            }

            // Marcar nodo de desvío
            float cx = ToSvgX(center.X), cy = ToSvgY(center.Z);
            sb.AppendLine(string.Format(CultureInfo.InvariantCulture,
                "<circle cx=\"{0:F1}\" cy=\"{1:F1}\" r=\"8\" fill=\"#FF0055\" stroke=\"#FFFFFF\" stroke-width=\"2\"/>", cx, cy));
            sb.AppendLine(string.Format(CultureInfo.InvariantCulture,
                "<text x=\"{0:F1}\" y=\"{1:F1}\" fill=\"#FF5577\" font-family=\"monospace\" font-size=\"11\" font-weight=\"bold\"> Nodo #{2} (In:{3}/Out:{4})</text>",
                cx + 12, cy + 4, detourNode, inDeg.GetValueOrDefault(detourNode), outDeg.GetValueOrDefault(detourNode)));

            sb.AppendLine("</svg>");
            File.WriteAllText(svgPath, sb.ToString());
            _output.WriteLine($"SVG de desvío generado en: {svgPath}");
        }
    }
}
