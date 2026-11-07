using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using AtsGpsServer.Map;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class E2EJobValidationTests
    {
        private readonly ITestOutputHelper _output;

        public E2EJobValidationTests(ITestOutputHelper output)
        {
            _output = output;
        }

        public record JobPair(string Origin, string Destination, string Cargo, ulong Income);

        [Fact]
        public void Validate_10_E2E_Job_Routes_Starting_With_SanFrancisco_Redding()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            if (!File.Exists(mapPath))
            {
                mapPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "data", "map_ats.bin");
            }
            Assert.True(File.Exists(mapPath), $"map_ats.bin no existe en {mapPath}");

            using var stream = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(stream);
            var finder = new PathFinder(graph);

            // Lista representativa de 12 trabajos reales de SCS empezando por San Francisco -> Redding
            var testJobs = new List<JobPair>
            {
                new("San Francisco", "Redding", "Envío Prioritario / Maquinaria", 1438),
                new("Redding", "Medford", "Carga General", 1520),
                new("Sacramento", "Stockton", "Vino y Bebidas Embotelladas", 1120),
                new("Stockton", "Modesto", "Alimentos Envasados", 890),
                new("Modesto", "Fresno", "Maquinaria Agrícola", 1750),
                new("Fresno", "Bakersfield", "Frutas y Verduras Frescas", 1980),
                new("Bakersfield", "Los Angeles", "Equipos Industriales", 2450),
                new("Oakland", "San Jose", "Electrónica y Microchips", 1350),
                new("Los Angeles", "Barstow", "Materiales de Construcción", 2100),
                new("Reno", "Carson City", "Componentes Eléctricos", 1200),
                new("Elko", "Winnemucca", "Minerales y Rocas", 2300),
                new("Ontario", "Boise", "Fertilizantes Orgánicos", 1850)
            };

            _output.WriteLine("====================================================================================================================================================");
            _output.WriteLine("| ID | ORIGEN              | DESTINO             | CARGA                       | INGRESO | DIST RUTA (KM) | LÍNEA RECTA (KM) | RATIO | NODOS | TIEMPO | ESTADO  |");
            _output.WriteLine("====================================================================================================================================================");

            int jobIdx = 1;
            int successCount = 0;

            foreach (var job in testJobs)
            {
                var citySrc = FindCity(graph, job.Origin);
                var cityDst = FindCity(graph, job.Destination);

                Assert.NotNull(citySrc);
                Assert.NotNull(cityDst);

                uint startNode = citySrc.NearestNodeIndex;
                uint targetNode = cityDst.NearestNodeIndex;

                var result = finder.FindRoute(startNode, targetNode);

                double straightLineDist = result.EuclideanStraightDistance / 1000.0;
                double routeDist = result.TotalLengthMeters / 1000.0;
                double ratio = result.DetourRatio;
                double timeMs = result.ExecutionTimeMicroseconds / 1000.0;

                string status = (result.Success && ratio <= 2.5) ? "EXITOSA" : (result.Success ? "DESVÍO" : "FALLO");
                if (result.Success && ratio <= 2.5) successCount++;

                _output.WriteLine($"| {jobIdx,2} | {job.Origin,-19} | {job.Destination,-19} | {job.Cargo,-27} | ${job.Income,6} | {routeDist,14:F2} | {straightLineDist,16:F2} | {ratio,5:F2} | {result.NodesExpanded,5} | {timeMs,5:F1}ms | {status,-7} |");
                jobIdx++;
            }

            _output.WriteLine("====================================================================================================================================================");
            _output.WriteLine($"TOTAL TRABAJOS VALIDADOS: {testJobs.Count} | EXITOSOS CON RATIO <= 2.5: {successCount}/{testJobs.Count} ({successCount * 100.0 / testJobs.Count:F1}%)");

            Assert.True(successCount >= 10, $"Se esperaban al menos 10 rutas exitosas con ratio <= 2.5, se obtuvieron {successCount}");
        }

        private static MapCity? FindCity(MapGraph graph, string name)
        {
            string clean = name.ToLowerInvariant().Replace(" ", "_").Replace("-", "_");
            return graph.Cities.FirstOrDefault(c =>
            {
                string cClean = c.Name.ToLowerInvariant().Replace(" ", "_").Replace("-", "_");
                return cClean.Equals(clean) || cClean.StartsWith(clean) || clean.StartsWith(cClean) ||
                       (clean.Contains("francisco") && cClean.Contains("francisc"));
            });
        }
    }
}
