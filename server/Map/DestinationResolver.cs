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

            static string Norm(string s) => s.ToLowerInvariant().Replace("_", "").Replace(" ", "").Replace("-", "");

            string normCity = Norm(cleanCity);
            string normComp = cleanComp != null ? Norm(cleanComp) : string.Empty;

            // 1. Si hay empresa especificada, buscar coincidencia por empresa Y ciudad
            if (!string.IsNullOrEmpty(normComp))
            {
                var matchBoth = graph.Companies.FirstOrDefault(c =>
                {
                    string cCity = Norm(c.CityName);
                    string cName = Norm(c.Name);
                    bool cityMatches = cCity.Contains(normCity) || normCity.Contains(cCity);
                    bool compMatches = cName.Contains(normComp) || normComp.Contains(cName) ||
                                      c.CompanyToken.ToString().Equals(cleanComp, StringComparison.OrdinalIgnoreCase);
                    return cityMatches && compMatches;
                });

                if (matchBoth != null)
                {
                    return (matchBoth.NearestNodeIndex, matchBoth.X, matchBoth.Z);
                }
            }

            // 2. Coincidencia por Ciudad destino
            var matchCity = graph.Cities.FirstOrDefault(c =>
            {
                string cNorm = Norm(c.Name);
                return cNorm == normCity || cNorm.Contains(normCity) || normCity.Contains(cNorm) ||
                       c.CityToken.ToString().Equals(cleanCity, StringComparison.OrdinalIgnoreCase);
            });

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
