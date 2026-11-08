using System;
using System.IO;
using System.Linq;
using System.Text;
using AtsGpsServer.Map;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class InspectNogalesFanLinesTest
    {
        private readonly ITestOutputHelper _output;

        public InspectNogalesFanLinesTest(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Inspect_Nogales_Fan_Lines_And_Histogram()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            double truckX = -73369.61;
            double truckZ = 28658.36;

            var r0 = graph.Edges.Where(e => e.RoadType == 0).Select(e => e.Length).ToList();
            var r3 = graph.Edges.Where(e => e.RoadType == 3).Select(e => e.Length).ToList();

            _output.WriteLine("=== HISTOGRAMA GLOBAL DE ARISTAS POR ROADTYPE ===");
            _output.WriteLine($"Carreteras (RoadType 0): {r0.Count} | Min: {r0.Min():F1} m | Max: {r0.Max():F1} m | Media: {r0.Average():F1} m");
            _output.WriteLine($"Prefabs    (RoadType 3): {r3.Count} | Min: {r3.Min():F1} m | Max: {r3.Max():F1} m | Media: {r3.Average():F1} m");

            // Aristas de RoadType 3 con más de 150 m entre extremos
            var longPrefabEdges = graph.Edges
                .Where(e => e.RoadType == 3 && e.Length > 150f)
                .OrderByDescending(e => e.Length)
                .Take(25)
                .ToList();

            _output.WriteLine($"\nTotal aristas RoadType 3 > 150m: {graph.Edges.Count(e => e.RoadType == 3 && e.Length > 150f)}");
            _output.WriteLine("Top 25 aristas RoadType 3 más largas en todo el mapa:");
            foreach (var e in longPrefabEdges)
            {
                var n1 = graph.Nodes[(int)e.FromNodeIndex];
                var n2 = graph.Nodes[(int)e.ToNodeIndex];
                double dist = Math.Sqrt(Math.Pow(n1.X - n2.X, 2) + Math.Pow(n1.Z - n2.Z, 2));
                _output.WriteLine($"Edge {e.EdgeId}: Length={e.Length:F1} m, Dist={dist:F1} m, FromNode={e.FromNodeIndex} (X:{n1.X:F0}, Z:{n1.Z:F0}), ToNode={e.ToNodeIndex} (X:{n2.X:F0}, Z:{n2.Z:F0}), Points={e.Polyline.Count}");
            }

            // Aristas cerca del camión en Nogales (< 1500 m)
            var nearby = graph.Edges.Where(e => e.Polyline.Any(p => Math.Abs(p.X - truckX) < 1500 && Math.Abs(p.Z - truckZ) < 1500)).ToList();
            _output.WriteLine($"\nAristas a menos de 1500 m de Nogales: {nearby.Count}");
            _output.WriteLine($" - RoadType 0: {nearby.Count(e => e.RoadType == 0)}");
            _output.WriteLine($" - RoadType 3: {nearby.Count(e => e.RoadType == 3)}");

            // Imprimir las aristas de RoadType 3 en Nogales con longitud > 50m
            _output.WriteLine("\nAristas de Nogales (RoadType 3) con longitud > 50m:");
            foreach (var e in nearby.Where(e => e.RoadType == 3 && e.Length > 50f).OrderByDescending(e => e.Length).Take(30))
            {
                var n1 = graph.Nodes[(int)e.FromNodeIndex];
                var n2 = graph.Nodes[(int)e.ToNodeIndex];
                double dist = Math.Sqrt(Math.Pow(n1.X - n2.X, 2) + Math.Pow(n1.Z - n2.Z, 2));
                _output.WriteLine($"Edge {e.EdgeId}: Length={e.Length:F1} m, StraightDist={dist:F1} m, Points={e.Polyline.Count}, From={e.FromNodeIndex} (UID: {n1.Uid:X}), To={e.ToNodeIndex} (UID: {n2.Uid:X})");
            }

            // Conectores de mediana en Nogales (distancia <= 15m)
            var medianConnectors = nearby.Where(e => e.RoadType == 3 && e.Length <= 15.0f && e.Polyline.Count == 2).ToList();
            _output.WriteLine($"\nConectores de mediana en Nogales (<= 15m): {medianConnectors.Count}");

            GenerateNogalesSvg(nearby, truckX, truckZ);
        }

        private void GenerateNogalesSvg(System.Collections.Generic.List<MapEdge> edges, double tx, double tz)
        {
            var sb = new StringBuilder();
            sb.AppendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1000 1000\" width=\"1000\" height=\"1000\">");
            sb.AppendLine("  <rect width=\"1000\" height=\"1000\" fill=\"#070B14\"/>");
            sb.AppendLine("  <text x=\"30\" y=\"40\" fill=\"#38BDF8\" font-family=\"sans-serif\" font-size=\"18\" font-weight=\"bold\">ABANICO DE LÍNEAS EN NOGALES (PUNTO H4)</text>");

            double minX = tx - 1200;
            double maxX = tx + 1200;
            double minZ = tz - 1200;
            double maxZ = tz + 1200;

            float ToSvgX(double wx) => (float)((wx - minX) / (maxX - minX) * 900 + 50);
            float ToSvgY(double wz) => (float)((wz - minZ) / (maxZ - minZ) * 900 + 50);

            foreach (var e in edges)
            {
                string stroke = e.RoadType == 3 ? (e.Length > 150f ? "#EF4444" : "#F59E0B") : (e.RoadType == 0 ? "#38BDF8" : "#94A3B8");
                float w = e.RoadType == 3 ? (e.Length > 150f ? 2.5f : 1.2f) : 2.0f;

                for (int i = 0; i < e.Polyline.Count - 1; i++)
                {
                    float x1 = ToSvgX(e.Polyline[i].X);
                    float y1 = ToSvgY(e.Polyline[i].Z);
                    float x2 = ToSvgX(e.Polyline[i + 1].X);
                    float y2 = ToSvgY(e.Polyline[i + 1].Z);
                    sb.AppendLine($"  <line x1=\"{x1:F1}\" y1=\"{y1:F1}\" x2=\"{x2:F1}\" y2=\"{y2:F1}\" stroke=\"{stroke}\" stroke-width=\"{w:F1}\" opacity=\"0.7\"/>");
                }
            }

            // Camión
            float truckSvgX = ToSvgX(tx);
            float truckSvgY = ToSvgY(tz);
            sb.AppendLine($"  <circle cx=\"{truckSvgX:F1}\" cy=\"{truckSvgY:F1}\" r=\"8\" fill=\"#00E5FF\" stroke=\"#FFFFFF\" stroke-width=\"2\"/>");
            sb.AppendLine($"  <text x=\"{truckSvgX + 12:F1}\" y=\"{truckSvgY + 4:F1}\" fill=\"#FFFFFF\" font-family=\"sans-serif\" font-size=\"14\" font-weight=\"bold\">Camión</text>");
            sb.AppendLine("</svg>");

            File.WriteAllText(@"C:\Users\gmlve\Downloads\Fan_Lines_Nogales_H4.svg", sb.ToString(), Encoding.UTF8);
            _output.WriteLine("SVG de Nogales guardado en C:\\Users\\gmlve\\Downloads\\Fan_Lines_Nogales_H4.svg");
        }
    }
}
