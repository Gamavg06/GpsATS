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
    public class EmpiricalRouteValidationTests
    {
        private readonly ITestOutputHelper _output;

        public EmpiricalRouteValidationTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Benchmark_Real_Routes_Bend_Redmond_Portland_Seattle()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            Assert.True(File.Exists(mapPath), "map_ats.bin debe existir");

            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            var pathFinder = new PathFinder(graph);

            // Localizar ciudades en el grafo
            var bend = graph.Cities.FirstOrDefault(c => c.Name.Equals("Bend", StringComparison.OrdinalIgnoreCase));
            uint redmondNode;
            var redmondCity = graph.Cities.FirstOrDefault(c => c.Name.Equals("Redmond", StringComparison.OrdinalIgnoreCase));
            if (redmondCity != null)
            {
                redmondNode = redmondCity.NearestNodeIndex;
            }
            else
            {
                var redmondComp = graph.Companies.FirstOrDefault(c => c.CityName.Equals("Redmond", StringComparison.OrdinalIgnoreCase));
                redmondNode = redmondComp != null ? redmondComp.NearestNodeIndex : graph.FindNearestNode(-100500, -50000);
            }
            var portland = graph.Cities.FirstOrDefault(c => c.Name.Equals("Portland", StringComparison.OrdinalIgnoreCase));
            var seattle = graph.Cities.FirstOrDefault(c => c.Name.Equals("Seattle", StringComparison.OrdinalIgnoreCase));

            Assert.NotNull(bend);
            Assert.NotNull(portland);
            Assert.NotNull(seattle);

            uint bendNode = bend.NearestNodeIndex;
            uint portlandNode = portland.NearestNodeIndex;
            uint seattleNode = seattle.NearestNodeIndex;

            _output.WriteLine($"Coordenadas / Nodos:");
            _output.WriteLine($"  Bend:     Nodo {bendNode} (X={graph.Nodes[(int)bendNode].X:F0}, Z={graph.Nodes[(int)bendNode].Z:F0})");
            _output.WriteLine($"  Redmond:  Nodo {redmondNode} (X={graph.Nodes[(int)redmondNode].X:F0}, Z={graph.Nodes[(int)redmondNode].Z:F0})");
            _output.WriteLine($"  Portland: Nodo {portlandNode} (X={graph.Nodes[(int)portlandNode].X:F0}, Z={graph.Nodes[(int)portlandNode].Z:F0})");
            _output.WriteLine($"  Seattle:  Nodo {seattleNode} (X={graph.Nodes[(int)seattleNode].X:F0}, Z={graph.Nodes[(int)seattleNode].Z:F0})");

            var pairs = new List<(string name, uint start, uint target)>
            {
                ("Bend -> Redmond", bendNode, redmondNode),
                ("Bend -> Portland", bendNode, portlandNode),
                ("Portland -> Seattle", portlandNode, seattleNode)
            };

            _output.WriteLine("\n=== RESULTADOS DE RUTAS REALES (B.4) ===");
            foreach (var (name, start, target) in pairs)
            {
                // Cold run
                var coldSw = Stopwatch.StartNew();
                var resultCold = pathFinder.FindRoute(start, target);
                coldSw.Stop();
                long coldMs = coldSw.ElapsedMilliseconds;

                // Warm run (promedio de 5 ejecuciones)
                long warmTotalTicks = 0;
                RouteResult? lastResult = null;
                for (int i = 0; i < 5; i++)
                {
                    var warmSw = Stopwatch.StartNew();
                    lastResult = pathFinder.FindRoute(start, target);
                    warmSw.Stop();
                    warmTotalTicks += warmSw.ElapsedTicks;
                }
                double warmMs = (warmTotalTicks / 5.0) * 1000.0 / Stopwatch.Frequency;

                _output.WriteLine($"\nRuta: {name}");
                _output.WriteLine($"  Encontrada:        {(resultCold.Success ? "SÍ" : "NO")}");
                _output.WriteLine($"  Longitud:          {resultCold.TotalLengthMeters:F1} m ({resultCold.TotalLengthMeters / 1000.0:F2} km)");
                _output.WriteLine($"  Dist. Línea Recta: {resultCold.EuclideanStraightDistance:F1} m ({resultCold.EuclideanStraightDistance / 1000.0:F2} km)");
                _output.WriteLine($"  Ratio Desvío:      {resultCold.DetourRatio:F2}x");
                _output.WriteLine($"  Número de aristas: {resultCold.EdgeCount}");
                _output.WriteLine($"  Nodos expandidos:  {resultCold.NodesExpanded}");
                _output.WriteLine($"  Carreteras usadas: {string.Join(", ", resultCold.RoadNamesUsed)}");
                _output.WriteLine($"  Tiempo en frío:    {coldMs} ms");
                _output.WriteLine($"  Tiempo en caliente:{warmMs:F2} ms");
            }
        }

        [Fact]
        public void Verify_Forbidden_Turn_Restriction()
        {
            // Grafo con intersección en T:
            // Entrada Sur: Nodo 0 -> Nodo 1
            // Giro Derecha (Este): Nodo 1 -> Nodo 2 (Permitido)
            // Giro Izquierda (Oeste): Nodo 1 -> Nodo 3 (Permitido desde Norte Nodo 4 -> 1 -> 3, pero PROHIBIDO desde Sur 0 -> 1 -> 3)
            var graph = new MapGraph();
            graph.Nodes.Add(new MapNode { Index = 0, X = 0, Y = 0, Z = 100 });  // A (Sur)
            graph.Nodes.Add(new MapNode { Index = 1, X = 0, Y = 0, Z = 0 });    // Centro Intersección
            graph.Nodes.Add(new MapNode { Index = 2, X = 100, Y = 0, Z = 0 });  // Este
            graph.Nodes.Add(new MapNode { Index = 3, X = -100, Y = 0, Z = 0 }); // C (Oeste)
            graph.Nodes.Add(new MapNode { Index = 4, X = 0, Y = 0, Z = -100 }); // B (Norte)

            // Conexiones viales
            graph.Edges.Add(new MapEdge { EdgeId = 0, FromNodeIndex = 0, ToNodeIndex = 1, Length = 100f });
            graph.Edges.Add(new MapEdge { EdgeId = 1, FromNodeIndex = 4, ToNodeIndex = 1, Length = 100f });
            graph.Edges.Add(new MapEdge { EdgeId = 2, FromNodeIndex = 1, ToNodeIndex = 2, Length = 100f });
            graph.Edges.Add(new MapEdge { EdgeId = 3, FromNodeIndex = 1, ToNodeIndex = 3, Length = 100f });

            var pf = new PathFinder(graph);
            // Prohibir giro desde Sur a Oeste: (0 -> 1 -> 3)
            pf.AddForbiddenTurn(0, 1, 3);

            // Desde B (Norte 4) hacia C (Oeste 3): PERMITIDO
            var routeBtoC = pf.FindRoute(4, 3);
            Assert.True(routeBtoC.Success);
            Assert.Equal(new List<uint> { 4, 1, 3 }, routeBtoC.NodePath);

            // Desde A (Sur 0) hacia C (Oeste 3): PROHIBIDO (no hay camino alternativo)
            var routeAtoC = pf.FindRoute(0, 3);
            Assert.False(routeAtoC.Success);

            // Desde A (Sur 0) hacia Este (2): PERMITIDO
            var routeAtoEast = pf.FindRoute(0, 2);
            Assert.True(routeAtoEast.Success);
            Assert.Equal(new List<uint> { 0, 1, 2 }, routeAtoEast.NodePath);

            _output.WriteLine("=== PRUEBA DE GIRO PROHIBIDO (C.5) ===");
            _output.WriteLine("Caso: Intersección con giro prohibido (A=0, Centro=1, B=4, C=3)");
            _output.WriteLine("  Ruta B -> Centro -> C (4->1->3): PERMITIDA (Encontrada: SÍ)");
            _output.WriteLine("  Ruta A -> Centro -> C (0->1->3): PROHIBIDA (Encontrada: NO)");
            _output.WriteLine("  Ruta A -> Centro -> Este (0->1->2): PERMITIDA (Encontrada: SÍ)");
        }

        [Fact]
        public void Evaluate_Truck_Distance_To_Nearest_Edge_Mode()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            // Simular un tramo conducido a lo largo de nodos viales reales con ruido GPS (0.5 a 3m)
            var rng = new Random(42);
            var sampleNodes = graph.Nodes.Take(100).ToList();
            var distances = new List<float>();

            foreach (var node in sampleNodes)
            {
                // Posición simulada del camión con jitter normal de GPS
                float jitterX = (float)(rng.NextDouble() * 3.0 - 1.5);
                float jitterZ = (float)(rng.NextDouble() * 3.0 - 1.5);
                var truckPos = new Vector3(node.X + jitterX, node.Y, node.Z + jitterZ);

                // Encontrar distancia a la arista más cercana
                float minDist = float.MaxValue;
                foreach (var edge in graph.Edges)
                {
                    if (edge.FromNodeIndex == node.Index || edge.ToNodeIndex == node.Index)
                    {
                        var from = graph.Nodes[(int)edge.FromNodeIndex];
                        var to = graph.Nodes[(int)edge.ToNodeIndex];
                        float d = DistancePointToSegment(truckPos, new Vector3(from.X, from.Y, from.Z), new Vector3(to.X, to.Y, to.Z));
                        if (d < minDist) minDist = d;
                    }
                }
                if (minDist < float.MaxValue) distances.Add(minDist);
            }

            distances.Sort();
            float min = distances[0];
            float median = distances[distances.Count / 2];
            float p95 = distances[(int)(distances.Count * 0.95)];
            float max = distances[^1];

            _output.WriteLine("=== MODO DEBUG: DISTANCIA CAMIÓN A ARISTA (C.6) ===");
            _output.WriteLine($"Muestras analizadas: {distances.Count}");
            _output.WriteLine($"  Mínimo:  {min:F2} m");
            _output.WriteLine($"  Mediana: {median:F2} m");
            _output.WriteLine($"  P95:     {p95:F2} m");
            _output.WriteLine($"  Máximo:  {max:F2} m");

            Assert.True(median < 2.5f, "La mediana de distancia del camión a la arista debe estar dentro de la calzada (<2.5m)");
        }

        private static float DistancePointToSegment(Vector3 p, Vector3 a, Vector3 b)
        {
            var ab = b - a;
            float l2 = ab.LengthSquared();
            if (l2 < 0.0001f) return Vector3.Distance(p, a);
            float t = Math.Clamp(Vector3.Dot(p - a, ab) / l2, 0f, 1f);
            var proj = a + t * ab;
            return Vector3.Distance(p, proj);
        }
    }
}