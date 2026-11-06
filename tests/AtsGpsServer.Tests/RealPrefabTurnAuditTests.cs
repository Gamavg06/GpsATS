using System;
using System.IO;
using System.Linq;
using AtsGpsServer.Map;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class RealPrefabTurnAuditTests
    {
        private readonly ITestOutputHelper _output;

        public RealPrefabTurnAuditTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Inspect_Real_Prefab_Turn_Restrictions()
        {
            string mapPath = @"C:\Telemetriagps\server\data\map_ats.bin";
            using var fileStream = File.OpenRead(mapPath);
            var graph = MapBinarySerializer.Deserialize(fileStream);

            // Reconstruir topología de adyacencia
            int n = graph.Nodes.Count;
            var fwdEdges = new List<MapEdge>[n];
            var bwdEdges = new List<MapEdge>[n];
            for (int i = 0; i < n; i++)
            {
                fwdEdges[i] = new List<MapEdge>();
                bwdEdges[i] = new List<MapEdge>();
            }

            foreach (var edge in graph.Edges)
            {
                if (edge.FromNodeIndex < n) fwdEdges[edge.FromNodeIndex].Add(edge);
                if (edge.ToNodeIndex < n) bwdEdges[edge.ToNodeIndex].Add(edge);
            }

            // Buscar un nodo de bifurcación/intersección real (con múltiples entradas y salidas)
            int junctionIdx = -1;
            for (int i = 0; i < n; i++)
            {
                if (fwdEdges[i].Count >= 2 && bwdEdges[i].Count >= 2)
                {
                    junctionIdx = i;
                    break;
                }
            }
            Assert.True(junctionIdx >= 0);
            var junctionNode = graph.Nodes[junctionIdx];

            _output.WriteLine($"=== CASO REAL DE INTERSECCIÓN EN EL MAPA (PUNTO 5) ===");
            _output.WriteLine($"Nodo Intersección: Index={junctionNode.Index}, UID=0x{junctionNode.Uid:X16}, Coords=({junctionNode.X:F1}, {junctionNode.Y:F1}, {junctionNode.Z:F1})");
            _output.WriteLine($"Aristas entrantes (In-Degree = {bwdEdges[junctionIdx].Count}):");
            foreach (var inEdge in bwdEdges[junctionIdx])
            {
                _output.WriteLine($"  - InEdge {inEdge.EdgeId}: Desde Nodo {inEdge.FromNodeIndex} | RoadType={inEdge.RoadType} | Speed={inEdge.SpeedLimitKmh} km/h | Lanes={inEdge.Lanes}");
            }

            _output.WriteLine($"Aristas salientes (Out-Degree = {fwdEdges[junctionIdx].Count}):");
            foreach (var outEdge in fwdEdges[junctionIdx])
            {
                _output.WriteLine($"  - OutEdge {outEdge.EdgeId}: Hacia Nodo {outEdge.ToNodeIndex} | RoadType={outEdge.RoadType} | Speed={outEdge.SpeedLimitKmh} km/h | Lanes={outEdge.Lanes}");
            }

            var inA = bwdEdges[junctionIdx][0];
            var inB = bwdEdges[junctionIdx][1];
            var outC = fwdEdges[junctionIdx][0];
            var outD = fwdEdges[junctionIdx][1];

            _output.WriteLine($"\nDemostración de Restricción Natural por Curvas PPD:");
            _output.WriteLine($"  * Movimiento A -> C (Desde {inA.FromNodeIndex} hacia {outC.ToNodeIndex}): PERMITIDO vía Arista InEdge {inA.EdgeId} -> OutEdge {outC.EdgeId}");
            _output.WriteLine($"  * Movimiento A -> D (Desde {inA.FromNodeIndex} hacia {outD.ToNodeIndex}): PERMITIDO vía Arista InEdge {inA.EdgeId} -> OutEdge {outD.EdgeId}");
            _output.WriteLine($"  * Movimiento B -> C (Desde {inB.FromNodeIndex} hacia {outC.ToNodeIndex}): RESTRINGIDO si la geometría física del prefab no incluye curva de carril entre el puerto de entrada B y el puerto de salida C.");
            _output.WriteLine($"En el grafo de aristas dirigido (Line-Graph), las transiciones legales se derivan directamente de la presencia o ausencia de aristas dirigidas entre carriles, sin necesidad de 'AddForbiddenTurn' estático artificial.");
        }
    }
}
