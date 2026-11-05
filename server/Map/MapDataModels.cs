using System.Collections.Generic;
using System.Numerics;
using System.Text.Json.Serialization;

namespace AtsGpsServer.Map
{
    public class MapNode
    {
        public uint Index { get; set; }
        public ulong Uid { get; set; }
        public float X { get; set; }
        public float Y { get; set; }
        public float Z { get; set; }
    }

    public class MapEdge
    {
        public uint EdgeId { get; set; }
        public uint FromNodeIndex { get; set; }
        public uint ToNodeIndex { get; set; }
        public float Length { get; set; }
        public float SpeedLimitKmh { get; set; }
        public byte RoadType { get; set; } // 0=Highway, 1=MajorRoad, 2=LocalStreet, 3=PrefabTurn/Ramp, 4=Service/Depot
        public byte Lanes { get; set; }
        public byte Flags { get; set; } // bit 0: OneWay, bit 1: HasTrafficLight, bit 2: Toll
        public List<Vector3> Polyline { get; set; } = new();
    }

    public class MapCity
    {
        public ulong CityToken { get; set; }
        public string Name { get; set; } = string.Empty;
        public float X { get; set; }
        public float Z { get; set; }
        public uint NearestNodeIndex { get; set; }
    }

    public class MapCompany
    {
        public ulong CompanyToken { get; set; }
        public ulong CityToken { get; set; }
        public string Name { get; set; } = string.Empty;
        public string CityName { get; set; } = string.Empty;
        public float X { get; set; }
        public float Z { get; set; }
        public uint NearestNodeIndex { get; set; }
    }

    public class MapPoi
    {
        public uint PoiId { get; set; }
        public byte PoiType { get; set; } // 1=GasStation (⛽), 2=RestStop (🛏️), 3=ServiceRepair (🔧), 4=Dealership (🏬), 5=Company (🏢), 6=TollGate (🛂)
        public string Name { get; set; } = string.Empty;
        public float X { get; set; }
        public float Y { get; set; }
        public float Z { get; set; }
    }

    public class MapGraph
    {
        public List<MapNode> Nodes { get; set; } = new();
        public List<MapEdge> Edges { get; set; } = new();
        public List<MapCity> Cities { get; set; } = new();
        public List<MapCompany> Companies { get; set; } = new();
        public List<MapPoi> Pois { get; set; } = new();

        public float MinX { get; set; }
        public float MaxX { get; set; }
        public float MinZ { get; set; }
        public float MaxZ { get; set; }

        // Búsqueda espacial del nodo más cercano (distancia euclidiana plana en X, Z)
        public uint FindNearestNode(float x, float z)
        {
            if (Nodes.Count == 0) return 0;
            uint bestIndex = 0;
            double bestDistSq = double.MaxValue;

            for (int i = 0; i < Nodes.Count; i++)
            {
                float dx = Nodes[i].X - x;
                float dz = Nodes[i].Z - z;
                double dSq = dx * dx + dz * dz;
                if (dSq < bestDistSq)
                {
                    bestDistSq = dSq;
                    bestIndex = (uint)i;
                }
            }
            return bestIndex;
        }
    }
}
