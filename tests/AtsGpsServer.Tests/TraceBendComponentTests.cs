using System;
using System.IO;
using System.Numerics;
using AtsGpsServer.Map;
using TruckLib;
using TruckLib.HashFs;
using TruckLib.Models.Ppd;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class TraceBendComponentTests
    {
        private readonly ITestOutputHelper _output;

        public TraceBendComponentTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Verify_Bitmask_Hypothesis_us87()
        {
            string atsDir = @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
            var extractor = new MapExtractor(atsDir);
            var mapFiles = extractor.GetMapScsFiles();
            var readers = new List<IHashFsReader>();
            foreach (var f in mapFiles) { try { readers.Add(HashFsReader.Open(f)); } catch { } }

            string baseScs = Path.Combine(atsDir, "base.scs");
            using var baseReader = File.Exists(baseScs) ? HashFsReader.Open(baseScs) : null;
            string defScs = Path.Combine(atsDir, "def.scs");
            using var defReader = File.Exists(defScs) ? HashFsReader.Open(defScs) : null;

            var allFs = new List<IFileSystem>(readers);
            if (baseReader != null) allFs.Add(baseReader);
            if (defReader != null) allFs.Add(defReader);
            var compositeFs = new AssetLoader(allFs.ToArray());

            var descMap = extractor.GetPrefabDescMap(readers);
            string ppdPath = descMap["us_87"];
            var desc = PrefabDescriptor.Open(ppdPath, compositeFs);

            _output.WriteLine("=== VERIFICACION DE BITMASK vs INPUT/OUTPUT LINES ===");
            for (int c = 0; c < desc.NavCurves.Count; c++)
            {
                var nc = desc.NavCurves[c];
                byte maskStart = nc.LeadsToNodes.Item1;
                byte maskEnd = nc.LeadsToNodes.Item3;

                int nodeStart = maskStart > 0 ? BitOperations.TrailingZeroCount(maskStart) : -1;
                int nodeEnd = maskEnd > 0 ? BitOperations.TrailingZeroCount(maskEnd) : -1;

                _output.WriteLine($"Curve {c}: MaskStart={maskStart} -> NodeStart={nodeStart} | MaskEnd={maskEnd} -> NodeEnd={nodeEnd}");
            }

            foreach (var r in readers) try { r.Dispose(); } catch { }
        }
    }
}
