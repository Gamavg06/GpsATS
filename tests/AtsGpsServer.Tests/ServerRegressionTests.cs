using System;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Numerics;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using AtsGpsServer.Input;
using AtsGpsServer.Map;
using AtsGpsServer.Security;
using AtsGpsServer.Telemetry;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace AtsGpsServer.Tests
{
    public class ServerRegressionTests
    {
        [Theory]
        [InlineData(0.0, 0.0)]
        [InlineData(0.25, 270.0)]
        [InlineData(0.50, 180.0)]
        [InlineData(0.75, 90.0)]
        [InlineData(1.0, 0.0)]
        [InlineData(-0.25, 90.0)]
        [InlineData(double.NaN, 0.0)]
        [InlineData(double.PositiveInfinity, 0.0)]
        [InlineData(double.NegativeInfinity, 0.0)]
        public void HeadingConversion_ScsCcwToAndroidCw_MapsAllCardinalAndNonFiniteValues(double rawHeading, double expectedDegrees)
        {
            double actual = ScsTelemetryReader.ConvertScsHeadingToCompassDegrees(rawHeading);
            Assert.Equal(expectedDegrees, actual, 1e-6);
        }

        [Fact]
        public void ReadSnapshot_ReadsCorrectedOffsets_AndSanitizesNaNAndInfinity()
        {
            string mmfName = $"Local\\SCSTelemetry_Test_{Guid.NewGuid():N}";
            using var mmf = MemoryMappedFile.CreateNew(mmfName, 32 * 1024);
            using var writer = mmf.CreateViewAccessor(0, 32 * 1024, MemoryMappedFileAccess.Write);

            // Zona 1: sdkActive = true
            writer.Write(0, true);
            writer.Write(8, 1700000123L);

            // CargoMass <= 0 en offset 748, valor basura en 1800 (no debe usarse fallback 1800)
            writer.Write(700, float.NaN); // scale -> fallback 20.0f
            writer.Write(704, float.PositiveInfinity); // fuelCapacity -> 0f
            writer.Write(740, float.NegativeInfinity); // engineRpmMax -> 0f
            writer.Write(748, -5.0f);
            writer.Write(1800, 12345.0f);

            // Velocidad, RPM, CruiseControlSpeed (offset 988)
            writer.Write(948, 25.0f); // 90 km/h
            writer.Write(952, float.NaN);
            writer.Write(988, 20.0f); // 72 km/h
            writer.Write(1000, float.NaN);
            writer.Write(1004, float.PositiveInfinity);
            writer.Write(1036, float.NaN);
            writer.Write(1040, float.NegativeInfinity);
            writer.Write(1044, float.NaN);
            writer.Write(1048, float.PositiveInfinity);
            writer.Write(1052, float.NaN);
            writer.Write(1060, float.NaN);
            writer.Write(1064, float.PositiveInfinity);
            writer.Write(1068, float.NaN);
            writer.Write(1468, float.NaN);
            writer.Write(1589, true);

            // Posicion y Orientacion (0.25 CCW -> 270 deg CW)
            writer.Write(2200, double.NaN);
            writer.Write(2208, double.PositiveInfinity);
            writer.Write(2216, -4500.5);
            writer.Write(2224, 0.25);
            writer.Write(2232, double.NegativeInfinity);
            writer.Write(2240, double.NaN);

            // Trailer: offset antiguo 6058 en true, offset real 6080 en false
            writer.Write(6058, true);
            writer.Write(6080, false);
            writer.Write(6152, 0.42f);

            using var reader = new ScsTelemetryReader(mmfName);
            var snap1 = reader.ReadSnapshot();

            Assert.True(snap1.Connected);
            Assert.Equal(20.0f, snap1.Scale);
            Assert.Equal(0f, snap1.CargoMassKg);
            Assert.Equal(90.0f, snap1.SpeedKmh, 2);
            Assert.Equal(72.0f, snap1.CruiseControlSpeed, 2);
            Assert.Equal(0.25, snap1.Heading, 6);
            Assert.Equal(270.0, snap1.HeadingDegrees, 6);
            Assert.False(snap1.TrailerAttached);
            Assert.Equal(0f, snap1.TrailerCargoDamage);

            // Debe serializar sin ArgumentException por NaN/Infinity
            string json1 = JsonSerializer.Serialize(snap1);
            Assert.NotEmpty(json1);

            // Ahora activar TrailerAttached en offset 6080 con NaN en un canal de desgaste
            writer.Write(6080, true);
            writer.Write(6152, 0.15f);
            writer.Write(6156, float.NaN);
            writer.Write(6160, 0.25f);
            writer.Write(6164, float.PositiveInfinity);

            var snap2 = reader.ReadSnapshot();
            Assert.True(snap2.TrailerAttached);
            Assert.Equal(0.15f, snap2.TrailerCargoDamage, 4);
            Assert.Equal(0f, snap2.TrailerWearChassis);
            Assert.Equal(0.25f, snap2.TrailerWearWheels, 4);
            Assert.Equal(0f, snap2.TrailerWearBody);
            Assert.NotEmpty(JsonSerializer.Serialize(snap2));
        }

        [Fact]
        public void IsGameConnected_TransitionsCleanly_OnStartAndSdkDisconnect()
        {
            string mmfName = $"Local\\SCSTelemetry_ConnTest_{Guid.NewGuid():N}";
            using var reader = new ScsTelemetryReader(mmfName);

            // Antes de abrir ATS
            Assert.False(reader.IsGameConnected);
            Assert.False(reader.CheckConnectionStatus());

            // ATS abre MMF con sdkActive = true
            using (var mmf = MemoryMappedFile.CreateNew(mmfName, 32 * 1024))
            using (var writer = mmf.CreateViewAccessor(0, 32 * 1024, MemoryMappedFileAccess.Write))
            {
                writer.Write(0, true);
                Assert.True(reader.IsGameConnected);

                // ATS cierra o desactiva SDK (sdkActive = false)
                writer.Write(0, false);
                Assert.False(reader.IsGameConnected);

                // Al reactivarse el SDK vuelve a true
                writer.Write(0, true);
                Assert.True(reader.IsGameConnected);

                // ReadSnapshot tambien libera el handle cuando !sdkActive
                writer.Write(0, false);
                var snap = reader.ReadSnapshot();
                Assert.False(snap.Connected);
                Assert.False(reader.IsGameConnected);
            }

            // Con MMF cerrado y handle liberado, sigue en false
            Assert.False(reader.IsGameConnected);
        }

        [Fact]
        public void MapManager_GetMapInfo_ReadsHeaderWithoutCachedGraph()
        {
            string tempDir = Path.Combine(Path.GetTempPath(), "AtsGpsMapInfoTest_" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(tempDir);
            try
            {
                string mapPath = Path.Combine(tempDir, "map_ats.bin");
                var graph = new MapGraph
                {
                    MinX = -100f,
                    MaxX = 200f,
                    MinZ = -300f,
                    MaxZ = 400f
                };
                graph.Nodes.Add(new MapNode { Index = 0, Uid = 1, X = 0f, Y = 0f, Z = 0f });
                graph.Nodes.Add(new MapNode { Index = 1, Uid = 2, X = 10f, Y = 0f, Z = 10f });
                graph.Edges.Add(new MapEdge
                {
                    EdgeId = 0,
                    FromNodeIndex = 0,
                    ToNodeIndex = 1,
                    Length = 14.14f,
                    SpeedLimitKmh = 90f,
                    Polyline = { new Vector3(0, 0, 0), new Vector3(10, 0, 10) }
                });
                graph.Cities.Add(new MapCity { CityToken = 10, Name = "Phoenix", X = 0f, Z = 0f, NearestNodeIndex = 0 });
                graph.Companies.Add(new MapCompany { CompanyToken = 20, CityToken = 10, Name = "Voltison", CityName = "Phoenix", X = 5f, Z = 5f, NearestNodeIndex = 1 });

                using (var fs = File.Create(mapPath))
                {
                    MapBinarySerializer.Serialize(graph, fs);
                }

                var manager = new MapManager(atsDirOverride: tempDir, mapFilePathOverride: mapPath);
                Assert.True(manager.HasValidMap);

                string json = JsonSerializer.Serialize(manager.GetMapInfo());
                using var doc = JsonDocument.Parse(json);
                Assert.True(doc.RootElement.GetProperty("exists").GetBoolean());
                Assert.True(doc.RootElement.GetProperty("valid").GetBoolean());
                Assert.False(doc.RootElement.GetProperty("mapBuilding").GetBoolean());
                Assert.Equal(2, doc.RootElement.GetProperty("nodeCount").GetInt32());
                Assert.Equal(1, doc.RootElement.GetProperty("edgeCount").GetInt32());
                Assert.Equal(1, doc.RootElement.GetProperty("cityCount").GetInt32());
                Assert.Equal(1, doc.RootElement.GetProperty("companyCount").GetInt32());
            }
            finally
            {
                try { Directory.Delete(tempDir, true); } catch { }
            }
        }

        [Fact]
        public async Task MapEndpoints_Return503OnDownloadAnd202OnBuild_WhileIsBuildingMap()
        {
            string tempDir = Path.Combine(Path.GetTempPath(), "AtsGpsBuildingTest_" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(tempDir);
            string mapPath = Path.Combine(tempDir, "map_ats.bin");
            const string pin = "739104";

            var mapManager = new MapManager(atsDirOverride: tempDir, mapFilePathOverride: mapPath);
            mapManager.SetBuildingForTest(true);

            var app = AtsServerBuilder.CreateWebApplication(
                args: Array.Empty<string>(),
                port: 0,
                listenAddress: IPAddress.Loopback,
                fixedPin: pin,
                suppressConsoleLogs: true,
                mapManager: mapManager);

            try
            {
                await app.StartAsync();
                var server = app.Services.GetRequiredService<IServer>();
                var baseUri = new Uri(server.Features.Get<IServerAddressesFeature>()!.Addresses.First());
                using var client = new HttpClient { BaseAddress = baseUri };

                // /api/status reporta mapBuilding = true
                var statusResp = await client.GetAsync("/api/status");
                Assert.Equal(HttpStatusCode.OK, statusResp.StatusCode);
                using (var statusDoc = JsonDocument.Parse(await statusResp.Content.ReadAsStringAsync()))
                {
                    Assert.True(statusDoc.RootElement.GetProperty("mapBuilding").GetBoolean());
                }

                // Pair para obtener token
                var pairResp = await client.PostAsync(
                    "/api/pair",
                    new StringContent(JsonSerializer.Serialize(new { pin }), Encoding.UTF8, "application/json"));
                Assert.Equal(HttpStatusCode.OK, pairResp.StatusCode);
                using var pairDoc = JsonDocument.Parse(await pairResp.Content.ReadAsStringAsync());
                string token = pairDoc.RootElement.GetProperty("token").GetString()!;
                client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);

                // /api/map/download debe devolver 503 Service Unavailable mientras se construye el mapa
                var downloadResp = await client.GetAsync("/api/map/download");
                Assert.Equal(HttpStatusCode.ServiceUnavailable, downloadResp.StatusCode);
                using (var dlDoc = JsonDocument.Parse(await downloadResp.Content.ReadAsStringAsync()))
                {
                    Assert.True(dlDoc.RootElement.GetProperty("building").GetBoolean());
                }

                // /api/map/build debe devolver 202 Accepted mientras ya esta corriendo
                var buildResp = await client.PostAsync("/api/map/build", new StringContent(string.Empty));
                Assert.Equal(HttpStatusCode.Accepted, buildResp.StatusCode);

                // Si termina el build sin generar archivo valido -> 404 (no entrega archivos corruptos)
                File.WriteAllText(mapPath, "corrupto");
                mapManager.SetBuildingForTest(false);
                var corruptResp = await client.GetAsync("/api/map/download");
                Assert.Equal(HttpStatusCode.NotFound, corruptResp.StatusCode);
            }
            finally
            {
                await app.StopAsync();
                await app.DisposeAsync();
                try { Directory.Delete(tempDir, true); } catch { }
            }
        }

        [Fact]
        public void ProgramHelpers_FilterVirtualAdapters_ApipaAndParseAtsDir()
        {
            Assert.True(Program.IsVirtualOrTunnelAdapter("vEthernet (WSL)", "Hyper-V Virtual Ethernet Adapter"));
            Assert.True(Program.IsVirtualOrTunnelAdapter("Tailscale", "Tailscale Tunnel"));
            Assert.True(Program.IsVirtualOrTunnelAdapter("Ethernet 2", "VMware Virtual Ethernet Adapter"));
            Assert.False(Program.IsVirtualOrTunnelAdapter("Wi-Fi", "Intel(R) Wi-Fi 6 AX201 160MHz"));

            Assert.True(Program.IsApipaAddress(IPAddress.Parse("169.254.12.34")));
            Assert.False(Program.IsApipaAddress(IPAddress.Parse("192.168.1.55")));

            Assert.True(Program.IsRfc1918PrivateIpv4(IPAddress.Parse("10.0.0.5")));
            Assert.True(Program.IsRfc1918PrivateIpv4(IPAddress.Parse("172.16.0.1")));
            Assert.True(Program.IsRfc1918PrivateIpv4(IPAddress.Parse("172.31.255.254")));
            Assert.True(Program.IsRfc1918PrivateIpv4(IPAddress.Parse("192.168.1.55")));
            Assert.False(Program.IsRfc1918PrivateIpv4(IPAddress.Parse("100.64.0.1")));
            Assert.False(Program.IsRfc1918PrivateIpv4(IPAddress.Parse("172.15.255.255")));
            Assert.False(Program.IsRfc1918PrivateIpv4(IPAddress.Parse("172.32.0.1")));
            Assert.False(Program.IsRfc1918PrivateIpv4(IPAddress.Parse("169.254.12.34")));
            Assert.False(Program.IsRfc1918PrivateIpv4(IPAddress.Parse("127.0.0.1")));

            Assert.Equal(@"D:\Games\ATS", AtsServerBuilder.ParseAtsDirArg(new[] { "--ats-dir", @"D:\Games\ATS" }));
            Assert.Equal(@"D:\Games\ATS", AtsServerBuilder.ParseAtsDirArg(new[] { @"--ats-dir=""D:\Games\ATS""" }));
        }

        [Fact]
        public void VirtualInputController_InputStructSize_MatchesWin32NativeSize()
        {
            int expectedBytes = Environment.Is64BitProcess ? 40 : 28;
            Assert.Equal(expectedBytes, VirtualInputController.GetInputStructSize());
        }
    }
}
