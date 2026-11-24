using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace AtsGpsServer.Map
{
    public class MapManager
    {
        private readonly string _mapFilePath;
        private readonly string _atsDir;
        private readonly object _lock = new();
        private readonly SemaphoreSlim _buildLock = new(1, 1);
        private MapGraph? _cachedGraph;

        public bool IsMapReady => File.Exists(_mapFilePath);
        public string MapFilePath => _mapFilePath;
        public string AtsDirectory => _atsDir;

        public MapManager()
        {
            _atsDir = AtsPathDetector.DetectAtsDirectory();

            // Buscar map_ats.bin en ubicaciones relativas a AppContext.BaseDirectory
            string[] candidates =
            {
                Path.Combine(AppContext.BaseDirectory, "data", "map_ats.bin"),
                Path.Combine(AppContext.BaseDirectory, "..", "data", "map_ats.bin"),
                Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "data", "map_ats.bin")
            };

            string? resolved = null;
            foreach (string candidate in candidates)
            {
                string fullPath = Path.GetFullPath(candidate);
                if (File.Exists(fullPath))
                {
                    resolved = fullPath;
                    break;
                }
            }

            if (resolved != null)
            {
                _mapFilePath = resolved;
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

        public async Task<MapGraph> BuildMapAsync(Action<string, int, int>? progressCallback = null)
        {
            await _buildLock.WaitAsync();
            try
            {
                return await Task.Run(() =>
                {
                    var extractor = new MapExtractor(_atsDir);
                    var graph = extractor.ExtractGraph(null, progressCallback);

                    string? dir = Path.GetDirectoryName(_mapFilePath);
                    if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
                    {
                        Directory.CreateDirectory(dir);
                    }

                    string tmpPath = _mapFilePath + ".tmp";
                    try
                    {
                        using (var fs = File.Create(tmpPath))
                        {
                            MapBinarySerializer.Serialize(graph, fs);
                        }

                        lock (_lock)
                        {
                            File.Move(tmpPath, _mapFilePath, overwrite: true);
                            _cachedGraph = graph;
                        }
                    }
                    catch
                    {
                        try { if (File.Exists(tmpPath)) File.Delete(tmpPath); } catch { }
                        throw;
                    }

                    return graph;
                });
            }
            finally
            {
                _buildLock.Release();
            }
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
