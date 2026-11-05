using System.IO;
using System.Linq;
using System.Numerics;
using AtsGpsServer.Map;
using Xunit;

namespace AtsGpsServer.Tests
{
    public class MapSerializationTests
    {
        [Fact]
        public void MapBinarySerializer_RoundtripPreservesIntegrity()
        {
            var original = new MapGraph
            {
                MinX = -105000f,
                MaxX = -100000f,
                MinZ = -55000f,
                MaxZ = -50000f
            };

            // 1. Agregar Nodos
            original.Nodes.Add(new MapNode { Index = 0, Uid = 1001, X = -104000f, Y = 10f, Z = -53000f });
            original.Nodes.Add(new MapNode { Index = 1, Uid = 1002, X = -103500f, Y = 12f, Z = -52500f });
            original.Nodes.Add(new MapNode { Index = 2, Uid = 1003, X = -103000f, Y = 15f, Z = -52000f });

            // 2. Agregar Aristas con polilíneas interpoladas
            original.Edges.Add(new MapEdge
            {
                EdgeId = 0,
                FromNodeIndex = 0,
                ToNodeIndex = 1,
                Length = 707.1f,
                SpeedLimitKmh = 105f,
                RoadType = 0, // Interestatal
                Lanes = 2,
                Flags = 1, // OneWay
                Polyline = new List<Vector3>
                {
                    new(-104000f, 10f, -53000f),
                    new(-103750f, 11f, -52750f),
                    new(-103500f, 12f, -52500f)
                }
            });

            original.Edges.Add(new MapEdge
            {
                EdgeId = 1,
                FromNodeIndex = 1,
                ToNodeIndex = 2,
                Length = 707.1f,
                SpeedLimitKmh = 45f,
                RoadType = 3, // Prefab turn
                Lanes = 1,
                Flags = 2, // Semaphore
                Polyline = new List<Vector3>
                {
                    new(-103500f, 12f, -52500f),
                    new(-103000f, 15f, -52000f)
                }
            });

            // 3. Agregar Ciudades y Empresas
            original.Cities.Add(new MapCity
            {
                CityToken = 554433,
                Name = "portland",
                X = -103850f,
                Z = -53620f,
                NearestNodeIndex = 0
            });

            original.Companies.Add(new MapCompany
            {
                CompanyToken = 998877,
                CityToken = 554433,
                Name = "wallbert",
                CityName = "portland",
                X = -103800f,
                Z = -53600f,
                NearestNodeIndex = 0
            });

            // Serializar en memoria
            using var ms = new MemoryStream();
            MapBinarySerializer.Serialize(original, ms);

            // Deserializar
            ms.Position = 0;
            var decoded = MapBinarySerializer.Deserialize(ms);

            // Verificaciones
            Assert.Equal(original.Nodes.Count, decoded.Nodes.Count);
            Assert.Equal(original.Edges.Count, decoded.Edges.Count);
            Assert.Equal(original.Cities.Count, decoded.Cities.Count);
            Assert.Equal(original.Companies.Count, decoded.Companies.Count);

            Assert.Equal(-104000f, decoded.Nodes[0].X);
            Assert.Equal(707.1f, decoded.Edges[0].Length, 1);
            Assert.Equal(3, decoded.Edges[0].Polyline.Count);
            Assert.Equal("portland", decoded.Cities[0].Name);
            Assert.Equal("wallbert", decoded.Companies[0].Name);
            Assert.Equal(0u, decoded.Companies[0].NearestNodeIndex);
        }
    }
}
