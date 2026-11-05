using System;
using System.Collections.Generic;
using System.Linq;

namespace AtsGpsServer.Map
{
    public class DestinationResolver
    {
        private readonly MapManager _mapManager;

        public DestinationResolver(MapManager mapManager)
        {
            _mapManager = mapManager;
        }

        public (uint NodeIndex, float X, float Z)? ResolveJobDestination(string cityDst, string? compDst = null)
        {
            var graph = _mapManager.GetGraph();
            if (graph == null) return null;

            if (string.IsNullOrWhiteSpace(cityDst))
            {
                return null;
            }

            string cleanCity = cityDst.Trim();
            string? cleanComp = compDst?.Trim();

            // 1. Si hay empresa especificada, buscar coincidencia por empresa y ciudad
            if (!string.IsNullOrWhiteSpace(cleanComp))
            {
                var match = graph.Companies.FirstOrDefault(c =>
                    (c.Name.Equals(cleanComp, StringComparison.OrdinalIgnoreCase) || c.CompanyToken.ToString().Equals(cleanComp, StringComparison.OrdinalIgnoreCase)) &&
                    (c.CityName.Equals(cleanCity, StringComparison.OrdinalIgnoreCase) || c.CityToken.ToString().Equals(cleanCity, StringComparison.OrdinalIgnoreCase)));

                if (match != null)
                {
                    return (match.NearestNodeIndex, match.X, match.Z);
                }

                // Coincidencia por nombre de empresa
                var matchCompOnly = graph.Companies.FirstOrDefault(c =>
                    c.Name.Contains(cleanComp, StringComparison.OrdinalIgnoreCase));

                if (matchCompOnly != null)
                {
                    return (matchCompOnly.NearestNodeIndex, matchCompOnly.X, matchCompOnly.Z);
                }
            }

            // 2. Coincidencia por Ciudad destino
            var matchCity = graph.Cities.FirstOrDefault(c =>
                c.Name.Equals(cleanCity, StringComparison.OrdinalIgnoreCase) ||
                c.CityToken.ToString().Equals(cleanCity, StringComparison.OrdinalIgnoreCase) ||
                c.Name.Contains(cleanCity, StringComparison.OrdinalIgnoreCase) ||
                cleanCity.Contains(c.Name, StringComparison.OrdinalIgnoreCase));

            if (matchCity != null)
            {
                return (matchCity.NearestNodeIndex, matchCity.X, matchCity.Z);
            }

            return null;
        }

        public IEnumerable<object> GetAllSelectableDestinations()
        {
            var graph = _mapManager.GetGraph();
            if (graph == null) return Enumerable.Empty<object>();

            var list = new List<object>();

            // Ciudades
            foreach (var c in graph.Cities)
            {
                list.Add(new
                {
                    type = "city",
                    name = c.Name,
                    cityName = c.Name,
                    x = c.X,
                    z = c.Z,
                    nodeIndex = c.NearestNodeIndex
                });
            }

            // Empresas
            foreach (var comp in graph.Companies)
            {
                list.Add(new
                {
                    type = "company",
                    name = comp.Name,
                    cityName = comp.CityName,
                    x = comp.X,
                    z = comp.Z,
                    nodeIndex = comp.NearestNodeIndex
                });
            }

            return list;
        }
    }
}
