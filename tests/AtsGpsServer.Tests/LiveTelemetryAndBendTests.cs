using System;
using System.IO;
using System.Linq;
using AtsGpsServer.Map;
using AtsGpsServer.Telemetry;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class LiveTelemetryAndBendTests
    {
        private readonly ITestOutputHelper _output;

        public LiveTelemetryAndBendTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Inspect_Live_Telemetry_And_Bend_Position()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            if (!File.Exists(mapPath))
            {
                mapPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "data", "map_ats.bin");
            }

            Assert.True(File.Exists(mapPath), $"map_ats.bin no existe en {mapPath}");
            using var fileStream = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fileStream);

            var bend = graph.Cities.FirstOrDefault(c => c.Name.Equals("Bend", StringComparison.OrdinalIgnoreCase));
            Assert.NotNull(bend);
            _output.WriteLine($"CIUDAD BEND: X={bend.X}, Z={bend.Z}, NearestNode={bend.NearestNodeIndex}");

            var bendNode = graph.Nodes[(int)bend.NearestNodeIndex];
            _output.WriteLine($"NODO ANCLADO BEND: Index={bend.NearestNodeIndex}, UID={bendNode.Uid:X}, X={bendNode.X}, Y={bendNode.Y}, Z={bendNode.Z}");

            // Contar aristas a menos de 5 km de Bend
            int nearbyEdges = graph.Edges.Count(e => e.Polyline.Any(p => Math.Abs(p.X - bend.X) < 5000 && Math.Abs(p.Z - bend.Z) < 5000));
            _output.WriteLine($"Aristas en un radio de 5 km de Bend: {nearbyEdges}");

            // Probar lectura de telemetría real del juego en ejecución (amtrucks)
            using var reader = new ScsTelemetryReader();
            var snapshot = reader.ReadSnapshot();
            _output.WriteLine($"TELEMETRÍA REAL SCS: Connected={snapshot.Connected}, Paused={snapshot.GamePaused}");
            _output.WriteLine($"POSICIÓN CAMIÓN: X={snapshot.PosX:F2}, Y={snapshot.PosY:F2}, Z={snapshot.PosZ:F2}");
            _output.WriteLine($"HEADING: {snapshot.HeadingDegrees:F2}°, SPEED: {snapshot.SpeedKmh:F1} km/h, GEAR: {snapshot.GearDashboard}");

            if (snapshot.Connected && (snapshot.PosX != 0 || snapshot.PosZ != 0))
            {
                // Distancia a la ciudad más cercana
                var closestCity = graph.Cities.OrderBy(c => Math.Pow(c.X - snapshot.PosX, 2) + Math.Pow(c.Z - snapshot.PosZ, 2)).First();
                double distCityM = Math.Sqrt(Math.Pow(closestCity.X - snapshot.PosX, 2) + Math.Pow(closestCity.Z - snapshot.PosZ, 2));
                _output.WriteLine($"Ciudad más cercana: {closestCity.Name} a {distCityM:F1} m");

                // Calcular distancia a la arista vial más cercana
                double minEdgeDist = double.MaxValue;
                MapEdge? bestEdge = null;
                foreach (var edge in graph.Edges)
                {
                    for (int i = 0; i < edge.Polyline.Count - 1; i++)
                    {
                        var p1 = edge.Polyline[i];
                        var p2 = edge.Polyline[i + 1];
                        double d = DistancePointToSegment(snapshot.PosX, snapshot.PosZ, p1.X, p1.Z, p2.X, p2.Z);
                        if (d < minEdgeDist)
                        {
                            minEdgeDist = d;
                            bestEdge = edge;
                        }
                    }
                }
                _output.WriteLine($"DISTANCIA A LA ARISTA MÁS CERCANA: {minEdgeDist:F2} metros");
                if (bestEdge != null)
                {
                    _output.WriteLine($"Arista id={bestEdge.EdgeId}, RoadType={bestEdge.RoadType}, Lanes={bestEdge.Lanes}, SpeedLimit={bestEdge.SpeedLimitKmh} km/h");
                }
            }
        }

        private static double DistancePointToSegment(double px, double pz, double x1, double z1, double x2, double z2)
        {
            double dx = x2 - x1;
            double dz = z2 - z1;
            double lenSq = dx * dx + dz * dz;
            if (lenSq < 1e-6)
            {
                return Math.Sqrt(Math.Pow(px - x1, 2) + Math.Pow(pz - z1, 2));
            }
            double t = Math.Clamp(((px - x1) * dx + (pz - z1) * dz) / lenSq, 0.0, 1.0);
            double projX = x1 + t * dx;
            double projZ = z1 + t * dz;
            return Math.Sqrt(Math.Pow(px - projX, 2) + Math.Pow(pz - projZ, 2));
        }
    }
}
