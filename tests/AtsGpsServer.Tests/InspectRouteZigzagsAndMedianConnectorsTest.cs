using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using AtsGpsServer.Map;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class InspectRouteZigzagsAndMedianConnectorsTest
    {
        private readonly ITestOutputHelper _output;

        public InspectRouteZigzagsAndMedianConnectorsTest(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Trace_Nogales_Colville_Route_And_Detect_Median_Crossings()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            var nogales = graph.Cities.FirstOrDefault(c => c.Name.Equals("Nogales", StringComparison.OrdinalIgnoreCase));
            var colville = graph.Cities.FirstOrDefault(c => c.Name.Equals("Colville", StringComparison.OrdinalIgnoreCase));
            var sf = graph.Cities.FirstOrDefault(c => c.Name.Equals("San Francisco", StringComparison.OrdinalIgnoreCase));
            var redding = graph.Cities.FirstOrDefault(c => c.Name.Equals("Redding", StringComparison.OrdinalIgnoreCase));

            Assert.NotNull(nogales);
            Assert.NotNull(colville);
            Assert.NotNull(sf);
            Assert.NotNull(redding);

            _output.WriteLine("=== 1. CON MAPA ORIGINAL (CON CRUCES ARTIFICIALES) ===");
            var finderOrig = new PathFinder(graph);
            var rNogalesOrig = finderOrig.FindRoute(nogales.NearestNodeIndex, colville.NearestNodeIndex);
            CountAndPrintCrossovers("Nogales -> Colville (ORIGINAL)", rNogalesOrig, graph);

            var rSfOrig = finderOrig.FindRoute(sf.NearestNodeIndex, redding.NearestNodeIndex);
            CountAndPrintCrossovers("San Francisco -> Redding (ORIGINAL)", rSfOrig, graph);

            _output.WriteLine("\n=== 2. SIN CRUCES ARTIFICIALES DE MEDIANA ===");
            // Crear grafo clonado sin aristas de crossover artificiales (RoadType == 3 && Polyline.Count == 2 && Length <= 15m)
            var cleanGraph = new MapGraph
            {
                Nodes = graph.Nodes,
                Cities = graph.Cities,
                Companies = graph.Companies
            };
            cleanGraph.Edges = graph.Edges.Where(e => !(e.RoadType == 3 && e.Polyline.Count == 2 && e.Length <= 15.0f)).ToList();

            var finderClean = new PathFinder(cleanGraph);
            var rNogalesClean = finderClean.FindRoute(nogales.NearestNodeIndex, colville.NearestNodeIndex);
            CountAndPrintCrossovers("Nogales -> Colville (CLEAN)", rNogalesClean, cleanGraph);

            var rSfClean = finderClean.FindRoute(sf.NearestNodeIndex, redding.NearestNodeIndex);
            CountAndPrintCrossovers("San Francisco -> Redding (CLEAN)", rSfClean, cleanGraph);
        }

        private void CountAndPrintCrossovers(string label, RouteResult result, MapGraph graph)
        {
            if (!result.Success)
            {
                _output.WriteLine($"{label}: RUTA NO ENCONTRADA");
                return;
            }

            int medianCrossoverCount = 0;
            for (int i = 0; i < result.EdgePath.Count; i++)
            {
                var edge = result.EdgePath[i];
                if (edge.RoadType == 3 && edge.Length <= 15.0f && edge.Polyline.Count == 2)
                {
                    medianCrossoverCount++;
                }
            }

            _output.WriteLine($"{label}: Éxito={result.Success}, Aristas={result.EdgeCount}, Longitud={result.TotalLengthMeters / 1000f:F1} km, Nodos Expandidos={result.NodesExpanded}, Cruces Mediana={medianCrossoverCount}");
        }
    }
}
