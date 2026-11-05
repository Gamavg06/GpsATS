using System;
using System.IO;
using System.Numerics;
using System.Text;

namespace AtsGpsServer.Map
{
    public static class MapBinarySerializer
    {
        private static readonly byte[] Magic = Encoding.ASCII.GetBytes("ATSMAP03");
        private const uint FormatVersion = 3;

        public static void Serialize(MapGraph graph, Stream outputStream)
        {
            using var writer = new BinaryWriter(outputStream, Encoding.UTF8, leaveOpen: true);

            // 1. Header
            writer.Write(Magic);
            writer.Write(FormatVersion);
            writer.Write((uint)graph.Nodes.Count);
            writer.Write((uint)graph.Edges.Count);
            writer.Write((uint)graph.Cities.Count);
            writer.Write((uint)graph.Companies.Count);
            writer.Write((uint)graph.Pois.Count);
            writer.Write(graph.MinX);
            writer.Write(graph.MaxX);
            writer.Write(graph.MinZ);
            writer.Write(graph.MaxZ);

            // 2. Nodes
            foreach (var node in graph.Nodes)
            {
                writer.Write(node.Uid);
                writer.Write(node.X);
                writer.Write(node.Y);
                writer.Write(node.Z);
            }

            // 3. Edges
            foreach (var edge in graph.Edges)
            {
                writer.Write(edge.FromNodeIndex);
                writer.Write(edge.ToNodeIndex);
                writer.Write(edge.Length);
                writer.Write(edge.SpeedLimitKmh);
                writer.Write(edge.RoadType);
                writer.Write(edge.Lanes);
                writer.Write(edge.Flags);

                ushort ptCount = (ushort)Math.Min(edge.Polyline.Count, ushort.MaxValue);
                writer.Write(ptCount);
                for (int i = 0; i < ptCount; i++)
                {
                    var pt = edge.Polyline[i];
                    writer.Write(pt.X);
                    writer.Write(pt.Y);
                    writer.Write(pt.Z);
                }
            }

            // 4. Cities
            foreach (var city in graph.Cities)
            {
                writer.Write(city.CityToken);
                writer.Write(city.Name ?? string.Empty);
                writer.Write(city.X);
                writer.Write(city.Z);
                writer.Write(city.NearestNodeIndex);
            }

            // 5. Companies
            foreach (var comp in graph.Companies)
            {
                writer.Write(comp.CompanyToken);
                writer.Write(comp.CityToken);
                writer.Write(comp.Name ?? string.Empty);
                writer.Write(comp.CityName ?? string.Empty);
                writer.Write(comp.X);
                writer.Write(comp.Z);
                writer.Write(comp.NearestNodeIndex);
            }

            // 6. POIs (Gas Stations, Rest Areas, Repair Services, Dealerships)
            foreach (var poi in graph.Pois)
            {
                writer.Write(poi.PoiId);
                writer.Write(poi.PoiType);
                writer.Write(poi.Name ?? string.Empty);
                writer.Write(poi.X);
                writer.Write(poi.Y);
                writer.Write(poi.Z);
            }

            writer.Flush();
        }

        public static MapGraph Deserialize(Stream inputStream)
        {
            using var reader = new BinaryReader(inputStream, Encoding.UTF8, leaveOpen: true);

            byte[] magic = reader.ReadBytes(8);
            string magicStr = Encoding.ASCII.GetString(magic);
            if (magicStr != "ATSMAP03" && magicStr != "ATSMAP02")
            {
                throw exciting("Formato de mapa inválido o versión incompatible: " + magicStr);
            }

            uint version = reader.ReadUInt32();
            uint nodeCount = reader.ReadUInt32();
            uint edgeCount = reader.ReadUInt32();
            uint cityCount = reader.ReadUInt32();
            uint companyCount = reader.ReadUInt32();
            uint poiCount = magicStr == "ATSMAP03" ? reader.ReadUInt32() : 0;

            var graph = new MapGraph
            {
                MinX = reader.ReadSingle(),
                MaxX = reader.ReadSingle(),
                MinZ = reader.ReadSingle(),
                MaxZ = reader.ReadSingle()
            };

            // Nodos
            graph.Nodes = new List<MapNode>((int)nodeCount);
            for (uint i = 0; i < nodeCount; i++)
            {
                graph.Nodes.Add(new MapNode
                {
                    Index = i,
                    Uid = reader.ReadUInt64(),
                    X = reader.ReadSingle(),
                    Y = reader.ReadSingle(),
                    Z = reader.ReadSingle()
                });
            }

            // Aristas
            graph.Edges = new List<MapEdge>((int)edgeCount);
            for (uint i = 0; i < edgeCount; i++)
            {
                var edge = new MapEdge
                {
                    EdgeId = i,
                    FromNodeIndex = reader.ReadUInt32(),
                    ToNodeIndex = reader.ReadUInt32(),
                    Length = reader.ReadSingle(),
                    SpeedLimitKmh = reader.ReadSingle(),
                    RoadType = reader.ReadByte(),
                    Lanes = reader.ReadByte(),
                    Flags = reader.ReadByte()
                };

                ushort ptCount = reader.ReadUInt16();
                edge.Polyline = new List<Vector3>(ptCount);
                for (int p = 0; p < ptCount; p++)
                {
                    edge.Polyline.Add(new Vector3(
                        reader.ReadSingle(),
                        reader.ReadSingle(),
                        reader.ReadSingle()
                    ));
                }

                graph.Edges.Add(edge);
            }

            // Ciudades
            graph.Cities = new List<MapCity>((int)cityCount);
            for (uint i = 0; i < cityCount; i++)
            {
                graph.Cities.Add(new MapCity
                {
                    CityToken = reader.ReadUInt64(),
                    Name = reader.ReadString(),
                    X = reader.ReadSingle(),
                    Z = reader.ReadSingle(),
                    NearestNodeIndex = reader.ReadUInt32()
                });
            }

            // Empresas
            graph.Companies = new List<MapCompany>((int)companyCount);
            for (uint i = 0; i < companyCount; i++)
            {
                graph.Companies.Add(new MapCompany
                {
                    CompanyToken = reader.ReadUInt64(),
                    CityToken = reader.ReadUInt64(),
                    Name = reader.ReadString(),
                    CityName = reader.ReadString(),
                    X = reader.ReadSingle(),
                    Z = reader.ReadSingle(),
                    NearestNodeIndex = reader.ReadUInt32()
                });
            }

            // POIs
            graph.Pois = new List<MapPoi>((int)poiCount);
            for (uint i = 0; i < poiCount; i++)
            {
                graph.Pois.Add(new MapPoi
                {
                    PoiId = reader.ReadUInt32(),
                    PoiType = reader.ReadByte(),
                    Name = reader.ReadString(),
                    X = reader.ReadSingle(),
                    Y = reader.ReadSingle(),
                    Z = reader.ReadSingle()
                });
            }

            return graph;
        }

        private static Exception exciting(string msg) => new InvalidDataException(msg);
    }
}
