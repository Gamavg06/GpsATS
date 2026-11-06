using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using TruckLib.HashFs;
using TruckLib.ScsMap;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class ExtractorDiagnosticsTests
    {
        private readonly ITestOutputHelper _output;

        public ExtractorDiagnosticsTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Compare_Sector_Alone_Vs_With_8_Neighbors()
        {
            string atsDir = @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
            var files = Directory.GetFiles(atsDir, "*.scs")
                .Where(f => {
                    string n = Path.GetFileName(f).ToLowerInvariant();
                    return n == "base.scs" || n == "def.scs" || n.StartsWith("base_map") || n.StartsWith("dlc_");
                })
                .ToList();

            var readers = new List<IHashFsReader>();
            foreach (var f in files)
            {
                try { readers.Add(HashFsReader.Open(f)); } catch { }
            }

            var assetLoader = new AssetLoader(readers.ToArray());

            // Elegir un sector representativo en Oregón / Washington (ej. Portland / Bend)
            // Bend está cerca de (-26, -11)
            var centerSector = new SectorCoordinate(-26, -11);

            // 1. Cargar sector solo
            int roadsAlone = 0, roadsNullFwdAlone = 0, roadsValidAlone = 0;
            try
            {
                var mapAlone = TruckLib.ScsMap.Map.Open("/map/usa.mbd", assetLoader, new[] { centerSector });
                var roadItems = mapAlone.MapItems.Values.OfType<Road>().ToList();
                roadsAlone = roadItems.Count;
                roadsNullFwdAlone = roadItems.Count(r => r.Node == null || r.ForwardNode == null);
                roadsValidAlone = roadItems.Count(r => r.Node != null && r.ForwardNode != null);
            }
            catch (Exception ex)
            {
                _output.WriteLine($"Error abriendo sector solo: {ex.Message}");
            }

            // 2. Cargar sector con sus 8 vecinos (ventana de 3x3 = 9 sectores)
            var cluster9 = new List<SectorCoordinate>();
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    cluster9.Add(new SectorCoordinate(centerSector.X + dx, centerSector.Z + dz));
                }
            }

            int roadsCluster = 0, roadsNullFwdCluster = 0, roadsValidCluster = 0;
            try
            {
                var mapCluster = TruckLib.ScsMap.Map.Open("/map/usa.mbd", assetLoader, cluster9);
                // Filtrar solo las carreteras que pertenecen al sector central
                var roadItems = mapCluster.MapItems.Values.OfType<Road>().ToList();
                roadsCluster = roadItems.Count;
                roadsNullFwdCluster = roadItems.Count(r => r.Node == null || r.ForwardNode == null);
                roadsValidCluster = roadItems.Count(r => r.Node != null && r.ForwardNode != null);
            }
            catch (Exception ex)
            {
                _output.WriteLine($"Error abriendo cluster de 9 sectores: {ex.Message}");
            }

            _output.WriteLine($"=== COMPARACIÓN SECTOR SOLO VS 8 VECINOS (Sector {centerSector.X}, {centerSector.Z}) ===");
            _output.WriteLine($"Sector SOLO:");
            _output.WriteLine($"  Carreteras totales: {roadsAlone}");
            _output.WriteLine($"  Carreteras con ForwardNode nulo: {roadsNullFwdAlone}");
            _output.WriteLine($"  Carreteras válidas con ambos nodos: {roadsValidAlone}");

            _output.WriteLine($"\nSector CON 8 VECINOS (Bloque 3x3 de 9 sectores):");
            _output.WriteLine($"  Carreteras totales en el bloque: {roadsCluster}");
            _output.WriteLine($"  Carreteras con ForwardNode nulo: {roadsNullFwdCluster}");
            _output.WriteLine($"  Carreteras válidas con ambos nodos: {roadsValidCluster}");

            foreach (var r in readers) r.Dispose();
        }
    }
}
