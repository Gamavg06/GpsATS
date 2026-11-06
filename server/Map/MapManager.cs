using System;
using System.IO;
using System.Threading.Tasks;

namespace AtsGpsServer.Map
{
    public class MapManager
    {
        private readonly string _mapFilePath;
        private readonly string _atsDir;
        private readonly object _lock = new();
        private MapGraph? _cachedGraph;

        public bool IsMapReady => File.Exists(_mapFilePath);
        public string MapFilePath => _mapFilePath;
        public string AtsDirectory => _atsDir;

        public MapManager()
        {
            _atsDir = @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
            
            // Buscar map_ats.bin en múltiples ubicaciones
            string candidate1 = Path.Combine(AppContext.BaseDirectory, "data", "map_ats.bin");
            string candidate2 = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "data", "map_ats.bin");
            string candidate3 = @"C:\Telemetriagps\server\data\map_ats.bin";

            if (File.Exists(candidate1))
            {
                _mapFilePath = Path.GetFullPath(candidate1);
            }
            else if (File.Exists(candidate2))
            {
                _mapFilePath = Path.GetFullPath(candidate2);
            }
            else if (File.Exists(candidate3))
            {
                _mapFilePath = candidate3;
            }
            else
            {
                string dataDir = Path.Combine(AppContext.BaseDirectory, "data");
                if (!Directory.Exists(dataDir)) Directory.CreateDirectory(dataDir);
                _mapFilePath = Path.Combine(dataDir, "map_ats.bin");
            }
        }

        public MapGraph? GetGraph()
        {
            lock (_lock)
            {
                if (_cachedGraph != null) return _cachedGraph;

                if (File.Exists(_mapFilePath))
                {
                    try
                    {
                        using var fs = File.OpenRead(_mapFilePath);
                        _cachedGraph = MapBinarySerializer.Deserialize(fs);
                        return _cachedGraph;
                    }
                    catch
                    {
                        return null;
                    }
                }
                return null;
            }
        }

        public Task<MapGraph> BuildMapAsync(Action<string, int, int>? progressCallback = null)
        {
            return Task.Run(() =>
            {
                var extractor = new MapExtractor(_atsDir);
                var graph = extractor.ExtractGraph(null, progressCallback);

                lock (_lock)
                {
                    using (var fs = File.Create(_mapFilePath))
                    {
                        MapBinarySerializer.Serialize(graph, fs);
                    }
                    _cachedGraph = graph;
                }

                return graph;
            });
        }

        public object GetMapInfo()
        {
            lock (_lock)
            {
                bool exists = File.Exists(_mapFilePath);
                long sizeBytes = exists ? new FileInfo(_mapFilePath).Length : 0;
                var g = _cachedGraph;

                return new
                {
                    exists = exists,
                    fileSizeBytes = sizeBytes,
                    fileSizeKb = sizeBytes / 1024.0,
                    nodeCount = g?.Nodes.Count ?? 0,
                    edgeCount = g?.Edges.Count ?? 0,
                    cityCount = g?.Cities.Count ?? 0,
                    companyCount = g?.Companies.Count ?? 0,
                    bounds = g != null ? new { minX = g.MinX, maxX = g.MaxX, minZ = g.MinZ, maxZ = g.MaxZ } : null
                };
            }
        }
    }
}
