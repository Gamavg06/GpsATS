using System;
using System.IO;
using System.Linq;
using System.Collections.Generic;
using TruckLib;
using TruckLib.HashFs;
using TruckLib.ScsMap;
using AtsGpsServer.Map;

class Program
{
    static void Main()
    {
        string atsDir = @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
        Console.WriteLine("Cargando sectores de all_sectors.txt...");
        string secText = File.ReadAllText(@"C:\Telemetriagps\all_sectors.txt");
        var sectors = new List<SectorCoordinate>();

        var matches = System.Text.RegularExpressions.Regex.Matches(secText, @"new\((-?\d+),\s*(-?\d+)\)");
        foreach (System.Text.RegularExpressions.Match m in matches)
        {
            int x = int.Parse(m.Groups[1].Value);
            int z = int.Parse(m.Groups[2].Value);
            sectors.Add(new SectorCoordinate(x, z));
        }

        Console.WriteLine($"Total sectores a extraer: {sectors.Count}");

        var extractor = new MapExtractor(atsDir);
        var graph = extractor.ExtractGraph(sectors, (msg, cur, tot) =>
        {
            if (cur % 25 == 0 || cur == tot)
                Console.WriteLine($"[{cur}/{tot}] {msg}");
        });

        Console.WriteLine($"\nGRAFO COMPLETO EXTRAÍDO:");
        Console.WriteLine($"  Nodos: {graph.Nodes.Count}");
        Console.WriteLine($"  Aristas: {graph.Edges.Count}");
        Console.WriteLine($"  Ciudades: {graph.Cities.Count}");
        Console.WriteLine($"  Empresas: {graph.Companies.Count}");
        Console.WriteLine($"  POIs: {graph.Pois.Count}");

        string p1 = @"C:\Telemetriagps\server\data\map_ats.bin";
        string p2 = @"C:\Telemetriagps\server\bin\Release\net10.0-windows\data\map_ats.bin";

        using (var fs = File.Create(p1))
        {
            MapBinarySerializer.Serialize(graph, fs);
        }
        File.Copy(p1, p2, overwrite: true);

        Console.WriteLine($"Guardado exitosamente en:\n  {p1} ({new FileInfo(p1).Length:N0} bytes)\n  {p2}");
    }
}
