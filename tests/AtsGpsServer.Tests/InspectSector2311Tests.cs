using System;
using System.IO;
using System.Linq;
using System.Numerics;
using System.Text;
using AtsGpsServer.Map;
using TruckLib.HashFs;
using TruckLib.ScsMap;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class InspectSector2311Tests
    {
        private readonly ITestOutputHelper _output;

        public InspectSector2311Tests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Test_List_All_Prefab_Sii_Files()
        {
            string atsDir = @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
            var extractor = new MapExtractor(atsDir);
            var dlcReaders = extractor.GetMapScsFiles().Select(HashFsReader.Open).ToList();
            string defScsPath = Path.Combine(atsDir, "def.scs");
            var readers = new List<IHashFsReader>();
            if (File.Exists(defScsPath)) readers.Add(HashFsReader.Open(defScsPath));
            readers.AddRange(dlcReaders);

            int totalDefinitions = 0;
            var descMap = new Dictionary<string, string>();
            var regex = new System.Text.RegularExpressions.Regex(
                @"prefab_model\s*:\s*prefab\.([a-zA-Z0-9_]+)\s*\{[^}]*prefab_desc\s*:\s*""([^""]+)""",
                System.Text.RegularExpressions.RegexOptions.Singleline);

            foreach (var r in readers)
            {
                if (r.DirectoryExists("/def/world"))
                {
                    var files = r.GetFiles("/def/world");
                    foreach (var f in files)
                    {
                        var fname = Path.GetFileName(f);
                        if (fname.StartsWith("prefab", StringComparison.OrdinalIgnoreCase) && fname.EndsWith(".sii", StringComparison.OrdinalIgnoreCase))
                        {
                            var bytes = r.Extract(f)[0];
                            string content = System.Text.Encoding.UTF8.GetString(bytes);
                            int countBefore = descMap.Count;
                            foreach (System.Text.RegularExpressions.Match m in regex.Matches(content))
                            {
                                descMap[m.Groups[1].Value] = m.Groups[2].Value;
                            }
                            _output.WriteLine($"Archivo {f}: +{descMap.Count - countBefore} modelos (total acumulado={descMap.Count})");
                        }
                    }
                }
            }
            _output.WriteLine($"TOTAL MODELOS PREFAB CARGADOS: {descMap.Count}");
        }
    }
}
