using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.DependencyInjection;

namespace AtsGpsServer.Map
{
    public class MapManager
    {
        private string _mapFilePath;
        private readonly string _atsDir;
        private readonly object _lock = new();
        private readonly SemaphoreSlim _buildLock = new(1, 1);
        private MapGraph? _cachedGraph;
        private volatile bool _isBuilding;

        public bool IsBuildingMap => _isBuilding;
        public bool HasValidMap => MapBinarySerializer.IsValidHeader(MapFilePath);
        public bool IsMapReady => HasValidMap;

        public string MapFilePath
        {
            get
            {
                lock (_lock)
                {
                    return _mapFilePath;
                }
            }
        }

        public string AtsDirectory => _atsDir;

        [ActivatorUtilitiesConstructor]
        public MapManager() : this(null, null)
        {
        }

        public MapManager(string? atsDirOverride, string? mapFilePathOverride = null)
        {
            _atsDir = !string.IsNullOrWhiteSpace(atsDirOverride)
                ? Path.GetFullPath(atsDirOverride.Trim().Trim('"'))
                : AtsPathDetector.DetectAtsDirectory();

            if (!string.IsNullOrWhiteSpace(mapFilePathOverride))
            {
                _mapFilePath = Path.GetFullPath(mapFilePathOverride);
                string? overrideDir = Path.GetDirectoryName(_mapFilePath);
                if (!string.IsNullOrEmpty(overrideDir) && !Directory.Exists(overrideDir))
                {
                    Directory.CreateDirectory(overrideDir);
                }
                return;
            }

            string localAppDataMapPath = GetLocalAppDataMapPath();
            string[] candidates =
            {
                Path.Combine(AppContext.BaseDirectory, "data", "map_ats.bin"),
                Path.Combine(AppContext.BaseDirectory, "..", "data", "map_ats.bin"),
                Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "data", "map_ats.bin"),
                localAppDataMapPath
            };

            string? resolved = null;
            foreach (string candidate in candidates)
            {
                string fullPath = Path.GetFullPath(candidate);
                if (MapBinarySerializer.IsValidHeader(fullPath))
                {
                    resolved = fullPath;
                    break;
                }
            }

            if (resolved == null)
            {
                foreach (string candidate in candidates)
                {
                    string fullPath = Path.GetFullPath(candidate);
                    if (File.Exists(fullPath))
                    {
                        string? existingDir = Path.GetDirectoryName(fullPath);
                        if (!string.IsNullOrEmpty(existingDir) && CanWriteToDirectory(existingDir))
                        {
                            resolved = fullPath;
                            break;
                        }
                    }
                }
            }

            if (resolved != null)
            {
                _mapFilePath = resolved;
                return;
            }

            string defaultDataDir = Path.Combine(AppContext.BaseDirectory, "data");
            if (CanWriteToDirectory(defaultDataDir))
            {
                _mapFilePath = Path.Combine(defaultDataDir, "map_ats.bin");
            }
            else
            {
                string fallbackDir = Path.GetDirectoryName(localAppDataMapPath)!;
                if (!Directory.Exists(fallbackDir))
                {
                    Directory.CreateDirectory(fallbackDir);
                }
                _mapFilePath = localAppDataMapPath;
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
                        using var fs = new FileStream(_mapFilePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
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
            _isBuilding = true;
            try
            {
                return await Task.Run(() =>
                {
                    var extractor = new MapExtractor(_atsDir);
                    var graph = extractor.ExtractGraph(null, progressCallback);

                    string targetPath = ResolveWritableTargetPath();
                    string dir = Path.GetDirectoryName(targetPath) ?? AppContext.BaseDirectory;
                    if (!Directory.Exists(dir))
                    {
                        Directory.CreateDirectory(dir);
                    }

                    string tmpPath = Path.Combine(dir, $"map_ats.{Guid.NewGuid():N}.tmp");
                    try
                    {
                        using (var fs = File.Create(tmpPath))
                        {
                            MapBinarySerializer.Serialize(graph, fs);
                        }

                        MoveWithRetry(tmpPath, targetPath);

                        lock (_lock)
                        {
                            _mapFilePath = targetPath;
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
                _isBuilding = false;
                _buildLock.Release();
            }
        }

        internal void SetBuildingForTest(bool building)
        {
            _isBuilding = building;
        }

        public object GetMapInfo()
        {
            lock (_lock)
            {
                string mapPath = _mapFilePath;
                bool exists = File.Exists(mapPath);
                long sizeBytes = 0;
                if (exists)
                {
                    try { sizeBytes = new FileInfo(mapPath).Length; } catch { sizeBytes = 0; }
                }

                bool valid = exists && MapBinarySerializer.IsValidHeader(mapPath);
                var g = _cachedGraph;
                if (g != null)
                {
                    return new
                    {
                        exists = exists,
                        valid = valid,
                        mapBuilding = _isBuilding,
                        fileSizeBytes = sizeBytes,
                        fileSizeKb = sizeBytes / 1024.0,
                        nodeCount = g.Nodes.Count,
                        edgeCount = g.Edges.Count,
                        cityCount = g.Cities.Count,
                        companyCount = g.Companies.Count,
                        bounds = new { minX = g.MinX, maxX = g.MaxX, minZ = g.MinZ, maxZ = g.MaxZ }
                    };
                }

                if (exists && MapBinarySerializer.TryReadHeader(mapPath, out var header))
                {
                    return new
                    {
                        exists = exists,
                        valid = valid,
                        mapBuilding = _isBuilding,
                        fileSizeBytes = sizeBytes,
                        fileSizeKb = sizeBytes / 1024.0,
                        nodeCount = (int)header.NodeCount,
                        edgeCount = (int)header.EdgeCount,
                        cityCount = (int)header.CityCount,
                        companyCount = (int)header.CompanyCount,
                        bounds = new { minX = header.MinX, maxX = header.MaxX, minZ = header.MinZ, maxZ = header.MaxZ }
                    };
                }

                return new
                {
                    exists = exists,
                    valid = valid,
                    mapBuilding = _isBuilding,
                    fileSizeBytes = sizeBytes,
                    fileSizeKb = sizeBytes / 1024.0,
                    nodeCount = 0,
                    edgeCount = 0,
                    cityCount = 0,
                    companyCount = 0,
                    bounds = (object?)null
                };
            }
        }

        private string ResolveWritableTargetPath()
        {
            string currentPath = MapFilePath;
            string? currentDir = Path.GetDirectoryName(currentPath);
            if (!string.IsNullOrEmpty(currentDir) && CanWriteToDirectory(currentDir))
            {
                return currentPath;
            }

            return GetLocalAppDataMapPath();
        }

        private static string GetLocalAppDataMapPath()
        {
            string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            return Path.Combine(localAppData, "SGNIA", "AtsGps", "data", "map_ats.bin");
        }

        private static bool CanWriteToDirectory(string dirPath)
        {
            try
            {
                if (!Directory.Exists(dirPath))
                {
                    Directory.CreateDirectory(dirPath);
                }

                string probeFile = Path.Combine(dirPath, $".write_probe_{Guid.NewGuid():N}.tmp");
                using (File.Create(probeFile))
                {
                }
                File.Delete(probeFile);
                return true;
            }
            catch (UnauthorizedAccessException)
            {
                return false;
            }
            catch (IOException)
            {
                return false;
            }
            catch
            {
                return false;
            }
        }

        private static void MoveWithRetry(string sourceTmpPath, string destinationPath)
        {
            const int maxAttempts = 6;
            for (int attempt = 1; attempt <= maxAttempts; attempt++)
            {
                try
                {
                    File.Move(sourceTmpPath, destinationPath, overwrite: true);
                    return;
                }
                catch (Exception ex) when ((ex is IOException || ex is UnauthorizedAccessException) && attempt < maxAttempts)
                {
                    Thread.Sleep(50 * attempt);
                }
            }

            File.Move(sourceTmpPath, destinationPath, overwrite: true);
        }
    }
}
