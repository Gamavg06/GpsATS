using System;
using System.IO;
using System.Linq;
using AtsGpsServer.Map;
using TruckLib;
using TruckLib.HashFs;
using TruckLib.ScsMap;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class SectorBoundaryTests
    {
        private readonly ITestOutputHelper _output;

        public SectorBoundaryTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Inspect_Boundary_Roads()
        {
            string atsDir = @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
            var extractor = new MapExtractor(atsDir);
            var mapFiles = extractor.GetMapScsFiles();
            var readers = new List<IHashFsReader>();
            foreach (var f in mapFiles) { try { readers.Add(HashFsReader.Open(f)); } catch { } }
            var compositeMapFs = new AssetLoader(readers.ToArray());

            // Tomar dos sectores contiguos que dieron 0 nodos compartidos: (-21, -13) y (-21, -12)
            // Límite Z entre ellos es: -12 * 4000 = -48000
            float boundaryZ = -12 * 4000f;
            _output.WriteLine($"Límite Z entre (-21,-13) y (-21,-12): {boundaryZ}");

            var m1 = TruckLib.ScsMap.Map.Open("/map/usa.mbd", compositeMapFs, [new SectorCoordinate(-21, -13)]);
            var m2 = TruckLib.ScsMap.Map.Open("/map/usa.mbd", compositeMapFs, [new SectorCoordinate(-21, -12)]);

            var roads1 = m1.MapItems.Values.OfType<Road>().ToList();
            var roads2 = m2.MapItems.Values.OfType<Road>().ToList();

            _output.WriteLine($"Sector (-21, -13): {roads1.Count} carreteras");
            foreach (var r in roads1)
            {
                if (r.Node != null && r.ForwardNode != null)
                {
                    if (Math.Abs(r.Node.Position.Z - boundaryZ) < 500 || Math.Abs(r.ForwardNode.Position.Z - boundaryZ) < 500)
                    {
                        _output.WriteLine($"  Road1 {r.Uid}: Node=({r.Node.Position.X:F1},{r.Node.Position.Z:F1}) Uid={r.Node.Uid} -> Fwd=({r.ForwardNode.Position.X:F1},{r.ForwardNode.Position.Z:F1}) Uid={r.ForwardNode.Uid}");
                    }
                }
            }

            _output.WriteLine($"Sector (-21, -12): {roads2.Count} carreteras");
            foreach (var r in roads2)
            {
                if (r.Node != null && r.ForwardNode != null)
                {
                    if (Math.Abs(r.Node.Position.Z - boundaryZ) < 500 || Math.Abs(r.ForwardNode.Position.Z - boundaryZ) < 500)
                    {
                        _output.WriteLine($"  Road2 {r.Uid}: Node=({r.Node.Position.X:F1},{r.Node.Position.Z:F1}) Uid={r.Node.Uid} -> Fwd=({r.ForwardNode.Position.X:F1},{r.ForwardNode.Position.Z:F1}) Uid={r.ForwardNode.Uid}");
                    }
                }
            }

            foreach (var r in readers) try { r.Dispose(); } catch { }
        }
    }
}
