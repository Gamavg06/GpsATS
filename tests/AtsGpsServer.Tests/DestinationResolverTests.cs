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
    }
}
