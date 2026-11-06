using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using AtsGpsServer.Map;
using TruckLib.HashFs;
using TruckLib.ScsMap;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class FastSectorDiscoveryTests
    {
        private readonly ITestOutputHelper _output;

        public FastSectorDiscoveryTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Test_Fast_Sector_Discovery_From_Entries()
        {
            string atsDir = @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
            var extractor = new MapExtractor(atsDir);
            var mapFiles = extractor.GetMapScsFiles();
            _output.WriteLine($"Archivos SCS encontrados ({mapFiles.Count}): {string.Join(", ", mapFiles.Select(Path.GetFileName))}");

            var sectors = new HashSet<SectorCoordinate>();
            var secRegex = new Regex(@"sec([+-]\d{4})([+-]\d{4})\.base$", RegexOptions.IgnoreCase);

            var sw = System.Diagnostics.Stopwatch.StartNew();
            foreach (var scsPath in mapFiles)
            {
                try
                {
                    using var reader = HashFsReader.Open(scsPath);
                    // Buscar en /map/usa/
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
                catch (Exception ex)
                {
                    _output.WriteLine($"Error en {Path.GetFileName(scsPath)}: {ex.Message}");
                }
            }
            sw.Stop();

            _output.WriteLine($"Descubiertos {sectors.Count} sectores únicos en {sw.ElapsedMilliseconds} ms!");
            int minX = sectors.Min(s => s.X);
            int maxX = sectors.Max(s => s.X);
            int minZ = sectors.Min(s => s.Z);
            int maxZ = sectors.Max(s => s.Z);
            _output.WriteLine($"Límites: X in [{minX}, {maxX}], Z in [{minZ}, {maxZ}]");
            _output.WriteLine($"¿Contiene Bend (-26, -11)? {sectors.Contains(new SectorCoordinate(-26, -11))}");
            _output.WriteLine($"¿Contiene Seattle (-22, -19)? {sectors.Contains(new SectorCoordinate(-22, -19))}");
        }
    }
}
