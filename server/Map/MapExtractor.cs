using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Numerics;
using TruckLib;
using TruckLib.HashFs;
using TruckLib.ScsMap;
using TruckLib.Models.Ppd;

namespace AtsGpsServer.Map
{
    public class MapExtractor
    {
        private readonly string _atsDir;
        private readonly Dictionary<string, PrefabDescriptor?> _ppdCache = new();
        private readonly Dictionary<string, string> _prefabDescMap = new(StringComparer.OrdinalIgnoreCase);

        public MapExtractor(string atsDir)
        {
            _atsDir = atsDir;
        }

        public MapExtractor(string baseMapScsPath, string baseScsPath)
        {
            _atsDir = Path.GetDirectoryName(baseMapScsPath) ?? @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
        }

        public List<string> GetMapScsFiles()
        {
            var result = new List<string>();
            if (!Directory.Exists(_atsDir)) return result;

            var files = Directory.GetFiles(_atsDir, "*.scs");
            foreach (var file in files)
            {
                string name = Path.GetFileName(file).ToLowerInvariant();
                if (name.StartsWith("base_map"))
                {
                    result.Add(file);
                }
                else if (name.StartsWith("dlc_"))
                {
                    if (name == "dlc_arizona.scs" || name == "dlc_nevada.scs" ||
                        System.Text.RegularExpressions.Regex.IsMatch(name, @"^dlc_[a-z]{2}\.scs$"))
                    {
                        result.Add(file);
                    }
                }
            }
            return result.Distinct().OrderBy(f => f).ToList();
        }

        private void EnsurePrefabDefinitionsLoaded(IEnumerable<IHashFsReader> dlcReaders)
        {
            if (_prefabDescMap.Count > 0) return;

            string defScsPath = Path.Combine(_atsDir, "def.scs");
            var readers = new List<IHashFsReader>();
            if (File.Exists(defScsPath))
            {
                try { readers.Add(HashFsReader.Open(defScsPath)); } catch { }
            }
            readers.AddRange(dlcReaders);

            var regex = new System.Text.RegularExpressions.Regex(
                @"prefab_model\s*:\s*prefab\.([a-zA-Z0-9_]+)\s*\{[^}]*prefab_desc\s*:\s*""([^""]+)""",
                System.Text.RegularExpressions.RegexOptions.Singleline);

            foreach (var reader in readers)
            {
                try
                {
                    if (reader.EntryExists("/def/world/prefab.sii") != EntryType.NotFound)
                    {
                        var bytes = reader.Extract("/def/world/prefab.sii")[0];
                        string content = System.Text.Encoding.UTF8.GetString(bytes);
                        foreach (System.Text.RegularExpressions.Match m in regex.Matches(content))
                        {
                            _prefabDescMap[m.Groups[1].Value] = m.Groups[2].Value;
                        }
                    }
                }
                catch { }
            }
        }

        public Dictionary<string, string> GetPrefabDescMap(IEnumerable<IHashFsReader> dlcReaders)
        {
            EnsurePrefabDefinitionsLoaded(dlcReaders);
            return _prefabDescMap;
        }

        public List<SectorCoordinate> DiscoverSectors()
        {
            var sectors = new HashSet<SectorCoordinate>();
            var secRegex = new System.Text.RegularExpressions.Regex(@"sec([+-]\d{4})([+-]\d{4})\.base$", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
            var mapScsFiles = GetMapScsFiles();

            foreach (var scsPath in mapScsFiles)
            {
                try
                {
                    using var reader = HashFsReader.Open(scsPath);
                    if (reader.DirectoryExists("/map/usa"))
                    {
                        var entries = reader.GetFiles("/map/usa");
                        foreach (var e in entries)
                        {
                            var match = secRegex.Match(e);
                            if (match.Success)
                            {
                                int x = int.Parse(match.Groups[1].Value);
                                int z = int.Parse(match.Groups[2].Value);
                                sectors.Add(new SectorCoordinate(x, z));
                            }
                        }
                    }
                }
                catch { }
            }

            return sectors.OrderBy(s => s.X).ThenBy(s => s.Z).ToList();
        }

        public MapGraph ExtractGraph(IEnumerable<SectorCoordinate>? targetSectors = null, Action<string, int, int>? progressCallback = null)
        {
            var graph = new MapGraph();
            var uidToNodeIndex = new Dictionary<ulong, uint>();

            var readers = new List<IHashFsReader>();
            try
            {
                var mapScsFiles = GetMapScsFiles();
                foreach (var scsPath in mapScsFiles)
                {
                    try
                    {
                        readers.Add(HashFsReader.Open(scsPath));
                    }
                    catch
                    {
                        // Omitir paquetes que no sean HashFS válidos
                    }
                }

                if (readers.Count == 0)
                {
                    throw new FileNotFoundException("No se encontraron archivos de mapas .scs válidos en: " + _atsDir);
                }

                string baseScsPath = Path.Combine(_atsDir, "base.scs");
                using var baseReader = File.Exists(baseScsPath) ? HashFsReader.Open(baseScsPath) : null;
                string defScsPath = Path.Combine(_atsDir, "def.scs");
                using var defReader = File.Exists(defScsPath) ? HashFsReader.Open(defScsPath) : null;

                var allFs = new List<IFileSystem>(readers);
                if (baseReader != null) allFs.Add(baseReader);
                if (defReader != null) allFs.Add(defReader);

                var compositeMapFs = new AssetLoader(readers.ToArray());
                var compositePpdFs = new AssetLoader(allFs.ToArray());

                EnsurePrefabDefinitionsLoaded(readers);

                List<SectorCoordinate> sectorsToLoad;
                if (targetSectors != null)
                {
                    sectorsToLoad = targetSectors.ToList();
                }
                else
                {
                    sectorsToLoad = DiscoverSectors();
                }

                int currentSector = 0;
                int totalSectors = sectorsToLoad.Count;

                float minX = float.MaxValue, maxX = float.MinValue;
                float minZ = float.MaxValue, maxZ = float.MinValue;

                foreach (var sectorCoord in sectorsToLoad)
                {
                    currentSector++;
                    progressCallback?.Invoke($"Procesando sector ({sectorCoord.X}, {sectorCoord.Z})", currentSector, totalSectors);

                    TruckLib.ScsMap.Map map;
                    try
                    {
                        map = TruckLib.ScsMap.Map.Open("/map/usa.mbd", compositeMapFs, [sectorCoord]);
                    }
                    catch
                    {
                        continue;
                    }

                    var roads = map.MapItems.Values.OfType<Road>().ToList();
                    var prefabs = map.MapItems.Values.OfType<Prefab>().ToList();
                    var cityAreas = map.MapItems.Values.OfType<CityArea>().ToList();
                    var companies = map.MapItems.Values.OfType<Company>().ToList();
                    var services = map.MapItems.Values.OfType<Service>().ToList();
                    var fuelPumps = map.MapItems.Values.OfType<FuelPump>().ToList();
                    var garages = map.MapItems.Values.OfType<Garage>().ToList();

                    // 1. Carreteras (Roads)
                    foreach (var road in roads)
                    {
                        if (road.Node == null || road.ForwardNode == null) continue;

                        uint startIdx = GetOrAddNode(graph, uidToNodeIndex, road.Node);
                        uint endIdx = GetOrAddNode(graph, uidToNodeIndex, road.ForwardNode);

                        var pStart = new Vector3(road.Node.Position.X, road.Node.Position.Y, road.Node.Position.Z);
                        var pEnd = new Vector3(road.ForwardNode.Position.X, road.ForwardNode.Position.Y, road.ForwardNode.Position.Z);

                        UpdateBounds(pStart.X, pStart.Z, ref minX, ref maxX, ref minZ, ref maxZ);
                        UpdateBounds(pEnd.X, pEnd.Z, ref minX, ref maxX, ref minZ, ref maxZ);

                        float length = Vector3.Distance(pStart, pEnd);
                        if (length < 0.1f) continue;

                        var polylineFwd = GenerateRoadPolyline(pStart, pEnd);

                        graph.Edges.Add(new MapEdge
                        {
                            EdgeId = (uint)graph.Edges.Count,
                            FromNodeIndex = startIdx,
                            ToNodeIndex = endIdx,
                            Length = length,
                            SpeedLimitKmh = 105.0f,
                            RoadType = 0,
                            Lanes = 2,
                            Flags = 0,
                            Polyline = polylineFwd
                        });

                        var polylineBwd = new List<Vector3>(polylineFwd);
                        polylineBwd.Reverse();

                        graph.Edges.Add(new MapEdge
                        {
                            EdgeId = (uint)graph.Edges.Count,
                            FromNodeIndex = endIdx,
                            ToNodeIndex = startIdx,
                            Length = length,
                            SpeedLimitKmh = 105.0f,
                            RoadType = 0,
                            Lanes = 2,
                            Flags = 0,
                            Polyline = polylineBwd
                        });
                    }

                    // 2. Prefabs (Intersecciones, giros y rampas)
                    foreach (var prefab in prefabs)
                    {
                        if (prefab.Nodes == null || prefab.Nodes.Count < 2 || prefab.Nodes[0] == null) continue;

                        string modelToken = prefab.Model.ToString().TrimStart('/');
                        if (!_prefabDescMap.TryGetValue(modelToken, out string? ppdPath))
                        {
                            ppdPath = "/prefab/" + modelToken + ".ppd";
                        }
                        if (!ppdPath.EndsWith(".ppd")) ppdPath += ".ppd";

                        var desc = GetOrLoadPpd(ppdPath, compositePpdFs);
                        if (desc == null || desc.NavCurves.Count == 0) continue;

                        int origIdx = (int)prefab.Origin;
                        if (origIdx >= desc.Nodes.Count || origIdx >= prefab.Nodes.Count) origIdx = 0;
                        var ppdOriginPos = desc.Nodes[origIdx].Position;
                        var worldOrigNode = prefab.Nodes[origIdx];
                        if (worldOrigNode == null) continue;
                        var worldOrigin = new Vector3(worldOrigNode.Position.X, worldOrigNode.Position.Y, worldOrigNode.Position.Z);
                        var prefabRot = worldOrigNode.Rotation;

                        foreach (var nc in desc.NavCurves)
                        {
                            // En formato SCS PPD, LeadsToNodes.Item1 y Item3 son bitmasks (1 << nodeIndex)
                            int pStartIdx = nc.LeadsToNodes.Item1 > 0 ? System.Numerics.BitOperations.TrailingZeroCount(nc.LeadsToNodes.Item1) : -1;
                            int pEndIdx = nc.LeadsToNodes.Item3 > 0 ? System.Numerics.BitOperations.TrailingZeroCount(nc.LeadsToNodes.Item3) : -1;

                            if (pStartIdx >= 0 && pStartIdx < prefab.Nodes.Count &&
                                pEndIdx >= 0 && pEndIdx < prefab.Nodes.Count &&
                                pStartIdx != pEndIdx)
                            {
                                var nodeA = prefab.Nodes[pStartIdx];
                                var nodeB = prefab.Nodes[pEndIdx];
                                if (nodeA == null || nodeB == null) continue;

                                uint globalA = GetOrAddNode(graph, uidToNodeIndex, nodeA);
                                uint globalB = GetOrAddNode(graph, uidToNodeIndex, nodeB);

                                var worldStart = TransformLocalToWorld(nc.StartPosition, ppdOriginPos, worldOrigin, prefabRot);
                                var worldEnd = TransformLocalToWorld(nc.EndPosition, ppdOriginPos, worldOrigin, prefabRot);

                                var nodeAPos = new Vector3(nodeA.Position.X, nodeA.Position.Y, nodeA.Position.Z);
                                var nodeBPos = new Vector3(nodeB.Position.X, nodeB.Position.Y, nodeB.Position.Z);

                                var turnPolyline = new List<Vector3> { nodeAPos, worldStart, worldEnd, nodeBPos };

                                graph.Edges.Add(new MapEdge
                                {
                                    EdgeId = (uint)graph.Edges.Count,
                                    FromNodeIndex = globalA,
                                    ToNodeIndex = globalB,
                                    Length = nc.Length > 0 ? nc.Length : Vector3.Distance(worldStart, worldEnd),
                                    SpeedLimitKmh = 45.0f,
                                    RoadType = 3,
                                    Lanes = 1,
                                    Flags = (byte)(nc.SemaphoreId >= 0 ? 2 : 0),
                                    Polyline = turnPolyline
                                });
                            }
                        }
                    }

                    // 3. Ciudades (CityArea)
                    foreach (var city in cityAreas)
                    {
                        if (city.Node == null) continue;
                        string cityName = city.Name.ToString();
                        float cx = city.Node.Position.X;
                        float cz = city.Node.Position.Z;

                        if (!graph.Cities.Any(c => c.Name.Equals(cityName, StringComparison.OrdinalIgnoreCase)))
                        {
                            graph.Cities.Add(new MapCity
                            {
                                CityToken = city.Name.Value,
                                Name = char.ToUpper(cityName[0]) + cityName[1..],
                                X = cx,
                                Z = cz,
                                NearestNodeIndex = 0
                            });
                        }
                    }

                    // 4. Empresas (Company)
                    foreach (var comp in companies)
                    {
                        if (comp.Node == null) continue;
                        string compName = ResolveCompanyName(comp.CompanyName.ToString());
                        string cityName = comp.CityName.ToString();
                        float cx = comp.Node.Position.X;
                        float cz = comp.Node.Position.Z;

                        graph.Companies.Add(new MapCompany
                        {
                            CompanyToken = comp.CompanyName.Value,
                            CityToken = comp.CityName.Value,
                            Name = compName,
                            CityName = char.ToUpper(cityName[0]) + cityName[1..],
                            X = cx,
                            Z = cz,
                            NearestNodeIndex = 0
                        });

                        AddPoiDeduplicated(graph.Pois, 5, compName, cx, comp.Node.Position.Y, cz);
                    }

                    // 5. Servicios (Gasolineras, Talleres, Descanso, Concesionarios)
                    foreach (var s in services)
                    {
                        if (s.Node == null) continue;
                        byte poiType = s.ServiceType switch
                        {
                            ServiceType.GasStation => (byte)1,
                            ServiceType.Parking => (byte)2,
                            ServiceType.ServiceStation => (byte)3,
                            ServiceType.TruckDealer => (byte)4,
                            ServiceType.WeighStation or ServiceType.WeighStationCat => (byte)6,
                            _ => (byte)0
                        };
                        if (poiType == 0) continue;

                        string poiName = poiType switch
                        {
                            1 => "Gasolinera",
                            2 => "Zona Descanso",
                            3 => "Taller Mecánico",
                            4 => "Concesionario",
                            6 => "Báscula Pesaje",
                            _ => "Servicio"
                        };

                        AddPoiDeduplicated(graph.Pois, poiType, poiName, s.Node.Position.X, s.Node.Position.Y, s.Node.Position.Z);
                    }

                    // 6. FuelPumps
                    foreach (var fp in fuelPumps)
                    {
                        if (fp.Node == null) continue;
                        AddPoiDeduplicated(graph.Pois, 1, "Gasolinera", fp.Node.Position.X, fp.Node.Position.Y, fp.Node.Position.Z);
                    }

                    // 7. Garages
                    foreach (var grg in garages)
                    {
                        if (grg.Node == null) continue;
                        AddPoiDeduplicated(graph.Pois, 2, "Garaje / Descanso", grg.Node.Position.X, grg.Node.Position.Y, grg.Node.Position.Z);
                    }
                }

                // Calcular grados de entrada y salida para anclaje transitable (Punto 2)
                var inDegree = new Dictionary<uint, int>();
                var outDegree = new Dictionary<uint, int>();
                foreach (var edge in graph.Edges)
                {
                    outDegree[edge.FromNodeIndex] = outDegree.GetValueOrDefault(edge.FromNodeIndex) + 1;
                    inDegree[edge.ToNodeIndex] = inDegree.GetValueOrDefault(edge.ToNodeIndex) + 1;
                }

                foreach (var c in graph.Cities)
                {
                    c.NearestNodeIndex = FindBestAnchorNode(graph, c.X, c.Z, inDegree, outDegree);
                }

                foreach (var comp in graph.Companies)
                {
                    comp.NearestNodeIndex = FindBestAnchorNode(graph, comp.X, comp.Z, inDegree, outDegree);
                }

                graph.MinX = minX;
                graph.MaxX = maxX;
                graph.MinZ = minZ;
                graph.MaxZ = maxZ;

                progressCallback?.Invoke("Generación del grafo completada exitosamente.", totalSectors, totalSectors);
                return graph;
            }
            finally
            {
                foreach (var r in readers)
                {
                    try { r.Dispose(); } catch { }
                }
            }
        }

        private static uint FindBestAnchorNode(MapGraph graph, float x, float z, Dictionary<uint, int> inDegree, Dictionary<uint, int> outDegree)
        {
            uint bestNode = 0;
            double bestDistSq = double.MaxValue;
            double bestTransitableDistSq = double.MaxValue;
            uint bestTransitableNode = 0;

            for (int i = 0; i < graph.Nodes.Count; i++)
            {
                var n = graph.Nodes[i];
                double dx = n.X - x;
                double dz = n.Z - z;
                double dSq = dx * dx + dz * dz;

                if (dSq < bestDistSq)
                {
                    bestDistSq = dSq;
                    bestNode = (uint)i;
                }

                inDegree.TryGetValue((uint)i, out int inD);
                outDegree.TryGetValue((uint)i, out int outD);
                if (inD > 0 && outD > 0 && dSq < bestTransitableDistSq)
                {
                    bestTransitableDistSq = dSq;
                    bestTransitableNode = (uint)i;
                }
            }

            // Preferir nodo transitable si está a menos de 4.0 km del centro de ciudad/empresa
            if (bestTransitableNode != 0 && bestTransitableDistSq < 16_000_000)
            {
                return bestTransitableNode;
            }

            return bestNode;
        }

        private static void AddPoiDeduplicated(List<MapPoi> pois, byte poiType, string name, float x, float y, float z)
        {
            for (int i = 0; i < pois.Count; i++)
            {
                var existing = pois[i];
                if (existing.PoiType == poiType)
                {
                    float dx = existing.X - x;
                    float dz = existing.Z - z;
                    if (dx * dx + dz * dz < 1225f)
                    {
                        return;
                    }
                }
            }

            pois.Add(new MapPoi
            {
                PoiId = (uint)pois.Count,
                PoiType = poiType,
                Name = name,
                X = x,
                Y = y,
                Z = z
            });
        }

        private static string ResolveCompanyName(string token)
        {
            token = token.ToLowerInvariant().Trim();
            if (token.Contains("ven_whs")) return "Venture";
            if (token.Contains("pt_trk") || token.Contains("peterbilt")) return "Peterbilt";
            if (token.Contains("kw_trk") || token.Contains("kenworth")) return "Kenworth";
            if (token.Contains("dc_car") || token.Contains("drake")) return "Drake Car Dealer";
            if (token.Contains("gal_oil") || token.Contains("gallon")) return "Gallon Oil";
            if (token.Contains("pho_oil")) return "Phoenix Oil";
            if (token.Contains("cm_min") || token.Contains("coastline")) return "Coastline Mining";
            if (token.Contains("st_met") || token.Contains("steel")) return "Steel Depot";
            if (token.Contains("dg_wd") || token.Contains("deep")) return "Deep Grove";
            if (token.Contains("wal_mkt") || token.Contains("wallbert")) return "Wallbert";
            if (token.Contains("hs_mkt") || token.Contains("sht_mkt")) return "Heart's Market";
            if (token.Contains("port_tac")) return "Port of Tacoma";
            if (token.Contains("port_sea")) return "Port of Seattle";
            if (token.Contains("avs_met")) return "AVS Metal";
            if (token.Contains("hms_con")) return "HMS Machinery";
            if (token.Contains("sg_whs")) return "Sunshine Crops";
            if (token.Contains("re_train")) return "Rail Export";
            if (token.Contains("ent_whs")) return "Enterprises";

            var parts = token.Split('_', StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 0) return token.ToUpperInvariant();
            return string.Join(" ", parts.Select(p => char.ToUpper(p[0]) + p[1..]));
        }

        private uint GetOrAddNode(MapGraph graph, Dictionary<ulong, uint> uidToNodeIndex, INode scsNode)
        {
            if (uidToNodeIndex.TryGetValue(scsNode.Uid, out uint existingIndex))
            {
                return existingIndex;
            }

            uint newIndex = (uint)graph.Nodes.Count;
            graph.Nodes.Add(new MapNode
            {
                Index = newIndex,
                Uid = scsNode.Uid,
                X = scsNode.Position.X,
                Y = scsNode.Position.Y,
                Z = scsNode.Position.Z
            });

            uidToNodeIndex[scsNode.Uid] = newIndex;
            return newIndex;
        }

        private PrefabDescriptor? GetOrLoadPpd(string ppdPath, IFileSystem baseReader)
        {
            if (_ppdCache.TryGetValue(ppdPath, out var cached))
            {
                return cached;
            }

            try
            {
                var desc = PrefabDescriptor.Open(ppdPath, baseReader);
                _ppdCache[ppdPath] = desc;
                return desc;
            }
            catch
            {
                _ppdCache[ppdPath] = null;
                return null;
            }
        }

        private static Vector3 TransformLocalToWorld(Vector3 localPos, Vector3 ppdOriginPos, Vector3 worldOrigin, Quaternion rotation)
        {
            var relative = localPos - ppdOriginPos;
            var rotated = Vector3.Transform(relative, rotation);
            return worldOrigin + rotated;
        }

        private static List<Vector3> GenerateRoadPolyline(Vector3 start, Vector3 end)
        {
            var points = new List<Vector3>(5);
            points.Add(start);
            for (int i = 1; i <= 3; i++)
            {
                float t = i / 4.0f;
                points.Add(Vector3.Lerp(start, end, t));
            }
            points.Add(end);
            return points;
        }

        private static void UpdateBounds(float x, float z, ref float minX, ref float maxX, ref float minZ, ref float maxZ)
        {
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
    }
}
