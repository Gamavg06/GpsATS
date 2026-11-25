using System;
using System.IO;
using System.Numerics;
using AtsGpsServer.Map;
using Xunit;

namespace AtsGpsServer.Tests
{
    public class DestinationResolverTests
    {
        [Fact]
        public void DestinationResolver_ResolvesMatchingCompanyAndCity()
        {
            var manager = new MapManager();
            var resolver = new DestinationResolver(manager);

            // Dado el mapa generado previamente en server/data/map_ats.bin
            if (manager.IsMapReady)
            {
                var result = resolver.ResolveJobDestination("portland", "naf_oil_gst");
                Assert.NotNull(result);
                Assert.True(result.Value.NodeIndex > 0);

                var destinations = resolver.GetAllSelectableDestinations();
                Assert.NotEmpty(destinations);
            }
        }

        [Fact]
        public void DestinationResolver_ResolvesPunctuatedCitiesAndCompanies()
        {
            string tempDir = Path.Combine(Path.GetTempPath(), "AtsGpsDestResolverTest_" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(tempDir);
            try
            {
                string mapPath = Path.Combine(tempDir, "map_ats.bin");
                var graph = new MapGraph
                {
                    MinX = -100f,
                    MaxX = 500f,
                    MinZ = -100f,
                    MaxZ = 500f
                };
                graph.Nodes.Add(new MapNode { Index = 0, Uid = 1, X = 0f, Y = 0f, Z = 0f });
                graph.Nodes.Add(new MapNode { Index = 1, Uid = 2, X = 100f, Y = 0f, Z = 100f });
                graph.Nodes.Add(new MapNode { Index = 2, Uid = 3, X = 200f, Y = 0f, Z = 200f });
                graph.Nodes.Add(new MapNode { Index = 3, Uid = 4, X = 300f, Y = 0f, Z = 300f });
                graph.Edges.Add(new MapEdge
                {
                    EdgeId = 0,
                    FromNodeIndex = 0,
                    ToNodeIndex = 1,
                    Length = 141.4f,
                    SpeedLimitKmh = 90f,
                    Polyline = { new Vector3(0, 0, 0), new Vector3(100, 0, 100) }
                });

                graph.Cities.Add(new MapCity { CityToken = 101, Name = "St_george", X = 100f, Z = 100f, NearestNodeIndex = 1 });
                graph.Cities.Add(new MapCity { CityToken = 102, Name = "Coeur_dalene", X = 200f, Z = 200f, NearestNodeIndex = 2 });
                graph.Cities.Add(new MapCity { CityToken = 103, Name = "St_louis", X = 300f, Z = 300f, NearestNodeIndex = 3 });
                graph.Companies.Add(new MapCompany
                {
                    CompanyToken = 201,
                    CityToken = 103,
                    Name = "Heart's Market",
                    CityName = "St_louis",
                    X = 310f,
                    Z = 310f,
                    NearestNodeIndex = 3
                });

                using (var fs = File.Create(mapPath))
                {
                    MapBinarySerializer.Serialize(graph, fs);
                }

                var manager = new MapManager(atsDirOverride: tempDir, mapFilePathOverride: mapPath);
                var resolver = new DestinationResolver(manager);

                var stGeorge = resolver.ResolveJobDestination("St. George");
                Assert.NotNull(stGeorge);
                Assert.Equal(1u, stGeorge.Value.NodeIndex);
                Assert.Equal(100f, stGeorge.Value.X);
                Assert.Equal(100f, stGeorge.Value.Z);

                var coeurDalene = resolver.ResolveJobDestination("Coeur d'Alene");
                Assert.NotNull(coeurDalene);
                Assert.Equal(2u, coeurDalene.Value.NodeIndex);
                Assert.Equal(200f, coeurDalene.Value.X);
                Assert.Equal(200f, coeurDalene.Value.Z);

                var heartsMarket = resolver.ResolveJobDestination("St. Louis", "Heart's Market");
                Assert.NotNull(heartsMarket);
                Assert.Equal(3u, heartsMarket.Value.NodeIndex);
                Assert.Equal(310f, heartsMarket.Value.X);
            }
            finally
            {
                try { Directory.Delete(tempDir, true); } catch { }
            }
        }
    }
}
