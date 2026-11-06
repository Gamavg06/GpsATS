using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using AtsGpsServer.Map;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class ReanchoredRoutingTests
    {
        private readonly ITestOutputHelper _output;

        public ReanchoredRoutingTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Test_Portland_Seattle_Both_Directions_With_Reanchored_Nodes()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);
            var pf = new PathFinder(graph);

            // Reanclados:
            // Bend: 34861
            // Portland: 23308
            // Seattle: 32562
            uint bend = 34861;
            uint portland = 23308;
            uint seattle = 32562;

            var testPairs = new (string name, uint start, uint end)[]
            {
                ("Bend -> Portland", bend, portland),
                ("Portland -> Bend", portland, bend),
                ("Portland -> Seattle", portland, seattle),
                ("Seattle -> Portland", seattle, portland),
                ("Bend -> Seattle", bend, seattle),
                ("Seattle -> Bend", seattle, bend)
            };

            _output.WriteLine("=== RUTAS REANCLADAS EN AMBOS SENTIDOS ===");
            foreach (var (name, start, end) in testPairs)
            {
                var sw = Stopwatch.StartNew();
                var result = pf.FindRoute(start, end);
                sw.Stop();

                _output.WriteLine($"\nRuta: {name}");
                _output.WriteLine($"  Encontrada:        {(result.Success ? "SÍ" : "NO")}");
                if (result.Success)
                {
                    _output.WriteLine($"  Longitud:          {result.TotalLengthMeters:F1} m ({result.TotalLengthMeters / 1000f:F2} km)");
                    _output.WriteLine($"  Línea Recta:       {result.EuclideanStraightDistance:F1} m ({result.EuclideanStraightDistance / 1000f:F2} km)");
                    _output.WriteLine($"  Ratio Desvío:      {result.DetourRatio:F2}x");
                    _output.WriteLine($"  Aristas:           {result.EdgeCount}");
                }
                _output.WriteLine($"  Nodos Expandidos:  {result.NodesExpanded}");
                _output.WriteLine($"  Tiempo:            {sw.ElapsedMilliseconds} ms");
            }
        }
    }
}