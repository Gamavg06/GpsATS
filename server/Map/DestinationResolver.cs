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

            static string Norm(string s) => s.ToLowerInvariant().Replace("_", "").Replace(" ", "").Replace("-", "").Replace(".", "").Replace("'", "");

            string normCity = Norm(cleanCity);
            if (string.IsNullOrEmpty(normCity))
            {
                return null;
            }
            string normComp = cleanComp != null ? Norm(cleanComp) : string.Empty;

            // 1. Si hay empresa especificada, buscar coincidencia por empresa Y ciudad priorizando coincidencia exacta
            if (!string.IsNullOrEmpty(normComp))
            {
                MapCompany? bestMatch = null;
                int bestScore = 0;

                foreach (var c in graph.Companies)
                {
                    string cCity = Norm(c.CityName ?? string.Empty);
                    string cName = Norm(c.Name ?? string.Empty);

                    bool cityExact = !string.IsNullOrEmpty(cCity) && cCity == normCity;
                    bool cityPartial = !cityExact && !string.IsNullOrEmpty(cCity) &&
                                       (cCity.Contains(normCity) || normCity.Contains(cCity));
                    if (!cityExact && !cityPartial) continue;

                    bool compTokenExact = c.CompanyToken.ToString().Equals(cleanComp, StringComparison.OrdinalIgnoreCase);
                    bool compExact = compTokenExact || (!string.IsNullOrEmpty(cName) && cName == normComp);
                    bool compPartial = !compExact && !string.IsNullOrEmpty(cName) &&
                                         (cName.Contains(normComp) || normComp.Contains(cName));
                    if (!compExact && !compPartial) continue;

                    int score = (cityExact ? 2 : 1) * 10 + (compExact ? 2 : 1);
                    if (score > bestScore)
                    {
                        bestScore = score;
                        bestMatch = c;
                        if (score == 22) break; // Coincidencia exacta en ambos
                    }
                }

                if (bestMatch != null)
                {
                    return (bestMatch.NearestNodeIndex, bestMatch.X, bestMatch.Z);
                }
            }

            // 2. Coincidencia por Ciudad destino (priorizando coincidencia exacta sobre parcial)
            MapCity? partialCityMatch = null;
            foreach (var c in graph.Cities)
            {
                string cNorm = Norm(c.Name ?? string.Empty);
                bool isExact = (!string.IsNullOrEmpty(cNorm) && cNorm == normCity) ||
                               c.CityToken.ToString().Equals(cleanCity, StringComparison.OrdinalIgnoreCase);
                if (isExact)
                {
                    return (c.NearestNodeIndex, c.X, c.Z);
                }

                if (partialCityMatch == null && !string.IsNullOrEmpty(cNorm) &&
                    (cNorm.Contains(normCity) || normCity.Contains(cNorm)))
                {
                    partialCityMatch = c;
                }
            }

            if (partialCityMatch != null)
            {
                return (partialCityMatch.NearestNodeIndex, partialCityMatch.X, partialCityMatch.Z);
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
