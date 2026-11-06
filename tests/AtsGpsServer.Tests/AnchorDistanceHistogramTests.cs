using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Numerics;
using AtsGpsServer.Map;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class AnchorDistanceHistogramTests
    {
        private readonly ITestOutputHelper _output;

        public AnchorDistanceHistogramTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Compute_City_And_Company_Anchor_Distance_Histograms()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            var cityDists = new List<float>();
            foreach (var city in graph.Cities)
            {
                if (city.NearestNodeIndex < graph.Nodes.Count)
                {
                    var node = graph.Nodes[(int)city.NearestNodeIndex];
                    float dx = node.X - city.X;
                    float dz = node.Z - city.Z;
                    cityDists.Add(MathF.Sqrt(dx * dx + dz * dz));
                }
            }

            var companyDists = new List<float>();
            foreach (var comp in graph.Companies)
            {
                if (comp.NearestNodeIndex < graph.Nodes.Count)
                {
                    var node = graph.Nodes[(int)comp.NearestNodeIndex];
                    float dx = node.X - comp.X;
                    float dz = node.Z - comp.Z;
                    companyDists.Add(MathF.Sqrt(dx * dx + dz * dz));
                }
            }

            _output.WriteLine("=== HISTOGRAMA DE DISTANCIA DE ANCLAJE: CIUDADES (Total: " + cityDists.Count + ") ===");
            PrintHistogram(cityDists, new[] { 100f, 300f, 500f, 1000f, 1500f, 2000f, 3000f, 4000f });

            _output.WriteLine("\n=== HISTOGRAMA DE DISTANCIA DE ANCLAJE: EMPRESAS (Total: " + companyDists.Count + ") ===");
            PrintHistogram(companyDists, new[] { 25f, 50f, 100f, 200f, 300f, 500f, 1000f, 2000f });
        }

        private void PrintHistogram(List<float> dists, float[] buckets)
        {
            dists.Sort();
            float min = dists.Min();
            float max = dists.Max();
            float median = dists[dists.Count / 2];
            float p95 = dists[(int)(dists.Count * 0.95)];

            _output.WriteLine($"  Min: {min:F1} m | Mediana: {median:F1} m | P95: {p95:F1} m | Max: {max:F1} m");
            _output.WriteLine($"  | Rango de Distancia (m) | Cantidad | Porcentaje |");
            _output.WriteLine($"  | :--- | :---: | :---: |");

            float prev = 0f;
            foreach (var b in buckets)
            {
                int count = dists.Count(d => d >= prev && d < b);
                float pct = (float)count / dists.Count * 100f;
                _output.WriteLine($"  | {prev,6:F0} m - {b,6:F0} m | {count,8} | {pct,8:F1}% |");
                prev = b;
            }
            int lastCount = dists.Count(d => d >= prev);
            float lastPct = (float)lastCount / dists.Count * 100f;
            _output.WriteLine($"  | > {prev,6:F0} m         | {lastCount,8} | {lastPct,8:F1}% |");
        }
    }
}
