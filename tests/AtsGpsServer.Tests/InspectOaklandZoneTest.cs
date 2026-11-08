using System;
using System.IO;
using System.Linq;
using System.Numerics;
using AtsGpsServer.Map;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class InspectOaklandZoneTest
    {
        private readonly ITestOutputHelper _output;

        public InspectOaklandZoneTest(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Inspect_Oakland_Network_Coverage()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            var oakland = graph.Cities.FirstOrDefault(c => c.Name.Equals("Oakland", StringComparison.OrdinalIgnoreCase));
            Assert.NotNull(oakland);

            var oakNode = graph.Nodes[(int)oakland.NearestNodeIndex];
            _output.WriteLine($"Oakland City Token: {oakland.CityToken}, NearestNode: {oakland.NearestNodeIndex} at ({oakNode.X:F1}, {oakNode.Y:F1}, {oakNode.Z:F1})");

            // Buscar todos los nodos y aristas a menos de 5 km (radio urbano)
            float radius = 5000f;
            var center = new Vector3(oakNode.X, oakNode.Y, oakNode.Z);

            var nodeIndices = new HashSet<uint>();
            for (int i = 0; i < graph.Nodes.Count; i++)
            {
                var n = graph.Nodes[i];
                if (Vector3.Distance(new Vector3(n.X, n.Y, n.Z), center) <= radius)
                {
                    nodeIndices.Add((uint)i);
                }
            }

            var edgesInOakland = graph.Edges.Where(e => nodeIndices.Contains(e.FromNodeIndex) || nodeIndices.Contains(e.ToNodeIndex)).ToList();

            _output.WriteLine($"Nodos en radio 5 km de Oakland: {nodeIndices.Count}");
            _output.WriteLine($"Aristas en radio 5 km de Oakland: {edgesInOakland.Count}");

            var roadTypes = edgesInOakland.GroupBy(e => e.RoadType).Select(g => $"Tipo {g.Key}: {g.Count()} aristas");
            _output.WriteLine("Distribución por tipo: " + string.Join(", ", roadTypes));

            // Empresas en Oakland
            var companiesInOakland = graph.Companies.Where(c => c.CityToken == oakland.CityToken || Vector3.Distance(new Vector3(c.X, 0, c.Z), new Vector3(center.X, 0, center.Z)) <= radius).ToList();
            _output.WriteLine($"Empresas en Oakland: {companiesInOakland.Count}");
            foreach (var comp in companiesInOakland)
            {
                _output.WriteLine($" - {comp.Name} ({comp.CompanyToken}) en ({comp.X:F1}, {comp.Z:F1}) -> NearestNode {comp.NearestNodeIndex}");
            }
        }
    }
}
