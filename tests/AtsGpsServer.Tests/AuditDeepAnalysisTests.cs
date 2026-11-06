using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Numerics;
using AtsGpsServer.Map;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class AuditDeepAnalysisTests
    {
        private readonly ITestOutputHelper _output;

        public AuditDeepAnalysisTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Audit_Point1_Strongly_Connected_Components_Tarjan()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            int n = graph.Nodes.Count;
            var adj = new List<uint>[n];
            for (int i = 0; i < n; i++) adj[i] = new List<uint>();
            foreach (var e in graph.Edges)
            {
                if (e.FromNodeIndex < n && e.ToNodeIndex < n)
                {
                    adj[e.FromNodeIndex].Add(e.ToNodeIndex);
                }
            }

            // Tarjan's Strongly Connected Components
            var indices = new int[n];
            Array.Fill(indices, -1);
            var lowlink = new int[n];
            var onStack = new bool[n];
            var stack = new Stack<int>();
            int index = 0;
            var sccs = new List<List<int>>();

            for (int u = 0; u < n; u++)
            {
                if (indices[u] == -1)
                {
                    StrongConnect(u);
                }
            }

            void StrongConnect(int v)
            {
                indices[v] = index;
                lowlink[v] = index;
                index++;
                stack.Push(v);
                onStack[v] = true;

                foreach (var w in adj[v])
                {
                    int wi = (int)w;
                    if (indices[wi] == -1)
                    {
                        StrongConnect(wi);
                        lowlink[v] = Math.Min(lowlink[v], lowlink[wi]);
                    }
                    else if (onStack[wi])
                    {
                        lowlink[v] = Math.Min(lowlink[v], indices[wi]);
                    }
                }

                if (lowlink[v] == indices[v])
                {
                    var component = new List<int>();
                    while (true)
                    {
                        int w = stack.Pop();
                        onStack[w] = false;
                        component.Add(w);
                        if (w == v) break;
                    }
                    sccs.Add(component);
                }
            }

            // Ordenar por tamaño
            sccs.Sort((a, b) => b.Count.CompareTo(a.Count));

            _output.WriteLine("=== 1. COMPONENTES FUERTEMENTE CONEXAS (SCC - TARJAN) ===");
            _output.WriteLine($"Total nodos: {n}, Total aristas dirigidas: {graph.Edges.Count}");
            _output.WriteLine($"Total componentes fuertemente conexas (SCC): {sccs.Count}");
            _output.WriteLine($"Mayor componente fuertemente conexa (SCC #1): {sccs[0].Count} nodos ({sccs[0].Count * 100.0 / n:F2}%)");

            for (int i = 0; i < Math.Min(10, sccs.Count); i++)
            {
                _output.WriteLine($"  SCC #{i + 1}: {sccs[i].Count} nodos ({sccs[i].Count * 100.0 / n:F2}%)");
            }

            // Mapear cada nodo a su SCC id
            var nodeToScc = new int[n];
            for (int i = 0; i < sccs.Count; i++)
            {
                foreach (var node in sccs[i]) nodeToScc[node] = i;
            }

            var bend = graph.Cities.FirstOrDefault(c => c.Name.Equals("Bend", StringComparison.OrdinalIgnoreCase));
            var portland = graph.Cities.FirstOrDefault(c => c.Name.Equals("Portland", StringComparison.OrdinalIgnoreCase));
            var seattle = graph.Cities.FirstOrDefault(c => c.Name.Equals("Seattle", StringComparison.OrdinalIgnoreCase));

            if (bend != null) _output.WriteLine($"Bend (Nodo {bend.NearestNodeIndex}): Pertenece a SCC #{nodeToScc[bend.NearestNodeIndex] + 1} (Tamaño: {sccs[nodeToScc[bend.NearestNodeIndex]].Count} nodos)");
            if (portland != null) _output.WriteLine($"Portland (Nodo {portland.NearestNodeIndex}): Pertenece a SCC #{nodeToScc[portland.NearestNodeIndex] + 1} (Tamaño: {sccs[nodeToScc[portland.NearestNodeIndex]].Count} nodos)");
            if (seattle != null) _output.WriteLine($"Seattle (Nodo {seattle.NearestNodeIndex}): Pertenece a SCC #{nodeToScc[seattle.NearestNodeIndex] + 1} (Tamaño: {sccs[nodeToScc[seattle.NearestNodeIndex]].Count} nodos)");
        }

        [Fact]
        public void Audit_Point2_City_Anchoring_Analysis()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            int n = graph.Nodes.Count;
            var inDegree = new int[n];
            var outDegree = new int[n];
            foreach (var e in graph.Edges)
            {
                if (e.FromNodeIndex < n) outDegree[e.FromNodeIndex]++;
                if (e.ToNodeIndex < n) inDegree[e.ToNodeIndex]++;
            }

            int badAnchors = 0;
            var badList = new List<string>();

            foreach (var c in graph.Cities)
            {
                uint idx = c.NearestNodeIndex;
                int ind = inDegree[idx];
                int outd = outDegree[idx];

                if (ind == 0 || outd == 0)
                {
                    badAnchors++;
                    badList.Add($"{c.Name}: Nodo {idx} (In={ind}, Out={outd})");
                }
            }

            _output.WriteLine("=== 2. AUDITORÍA DE ANCLAJE DE CIUDADES ===");
            _output.WriteLine($"Total ciudades: {graph.Cities.Count}");
            _output.WriteLine($"Ciudades mal ancladas (In=0 o Out=0): {badAnchors} ({badAnchors * 100.0 / graph.Cities.Count:F1}%)");
            foreach (var item in badList.Take(15))
            {
                _output.WriteLine($"  [MAL ANCLAJE] {item}");
            }
        }

        [Fact]
        public void Audit_Point3_Investigate_Redmond_And_Neighboring_Cities()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fs = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fs);

            _output.WriteLine("=== 3. BÚSQUEDA DE REDMOND Y CIUDADES CERCANAS A BEND ===");
            var bend = graph.Cities.FirstOrDefault(c => c.Name.Equals("Bend", StringComparison.OrdinalIgnoreCase));
            Assert.NotNull(bend);
            _output.WriteLine($"Bend Coords: X={bend.X:F1}, Z={bend.Z:F1}, Nodo={bend.NearestNodeIndex}");

            // Buscar ciudades cercanas a Bend (< 60 km)
            var nearbyCities = new List<(string name, float distKm, float x, float z)>();
            foreach (var c in graph.Cities)
            {
                float dx = c.X - bend.X;
                float dz = c.Z - bend.Z;
                float distKm = MathF.Sqrt(dx * dx + dz * dz) / 1000f;
                if (distKm < 60f)
                {
                    nearbyCities.Add((c.Name, distKm, c.X, c.Z));
                }
            }
            nearbyCities.Sort((a, b) => a.distKm.CompareTo(b.distKm));
            foreach (var nc in nearbyCities)
            {
                _output.WriteLine($"  Ciudad cercana: {nc.name} a {nc.distKm:F2} km (X={nc.x:F0}, Z={nc.z:F0})");
            }

            // Buscar si Redmond aparece en Cities o Companies
            var redmondCity = graph.Cities.FirstOrDefault(c => c.Name.Contains("Redmond", StringComparison.OrdinalIgnoreCase));
            _output.WriteLine($"¿Existe 'Redmond' en graph.Cities? {(redmondCity != null ? "SÍ (" + redmondCity.Name + ")" : "NO")}");

            var redmondCompanies = graph.Companies.Where(c => c.CityName.Contains("Redmond", StringComparison.OrdinalIgnoreCase) || c.Name.Contains("Redmond", StringComparison.OrdinalIgnoreCase)).ToList();
            _output.WriteLine($"Empresas con 'Redmond': {redmondCompanies.Count}");
            foreach (var rc in redmondCompanies)
            {
                _output.WriteLine($"  Empresa: {rc.Name} en Ciudad: '{rc.CityName}' (X={rc.X:F0}, Z={rc.Z:F0})");
            }
        }
    }
}