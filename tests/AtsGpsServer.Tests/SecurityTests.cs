using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using AtsGpsServer.Map;
using AtsGpsServer.Security;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace AtsGpsServer.Tests
{
    public class SecurityTests
    {
        private const string TestPin = "482913";

        private sealed class RealTestServer : IAsyncDisposable
        {
            public WebApplication App { get; }
            public Uri BaseUri { get; }
            public HttpClient Client { get; }

            private RealTestServer(WebApplication app, Uri baseUri)
            {
                App = app;
                BaseUri = baseUri;
                Client = new HttpClient { BaseAddress = baseUri };
            }

            public static async Task<RealTestServer> StartAsync(string pin = TestPin)
            {
                var app = AtsServerBuilder.CreateWebApplication(
                    args: Array.Empty<string>(),
                    port: 0,
                    listenAddress: IPAddress.Loopback,
                    fixedPin: pin,
                    configurePreSecurity: webApp =>
                    {
                        webApp.Use(async (context, next) =>
                        {
                            if (context.Request.Headers.TryGetValue("X-Test-Remote-Ip", out var ipHeader) &&
                                IPAddress.TryParse(ipHeader.ToString(), out var testIp))
                            {
                                context.Connection.RemoteIpAddress = testIp;
                            }
                            await next();
                        });
                    },
                    suppressConsoleLogs: true);

                await app.StartAsync();

                var server = app.Services.GetRequiredService<IServer>();
                var addresses = server.Features.Get<IServerAddressesFeature>()!.Addresses;
                var baseUri = new Uri(addresses.First());

                return new RealTestServer(app, baseUri);
            }

            public async ValueTask DisposeAsync()
            {
                Client.Dispose();
                await App.StopAsync();
                await App.DisposeAsync();
            }
        }

        [Theory]
        [InlineData("127.0.0.1", true)]
        [InlineData("::1", true)]
        [InlineData("192.168.1.1", true)]
        [InlineData("192.168.100.254", true)]
        [InlineData("10.0.0.1", true)]
        [InlineData("10.255.255.255", true)]
        [InlineData("172.16.0.1", true)]
        [InlineData("172.24.50.100", true)]
        [InlineData("172.31.255.254", true)]
        [InlineData("169.254.1.10", false)]
        [InlineData("169.254.255.254", false)]
        [InlineData("8.8.8.8", false)]
        [InlineData("1.1.1.1", false)]
        [InlineData("172.15.255.255", false)]
        [InlineData("172.32.0.1", false)]
        [InlineData("200.50.10.5", false)]
        public void IpWhitelist_ValidatesPrivateSubnetsCorrectly(string ipString, bool expectedAllowed)
        {
            var ip = IPAddress.Parse(ipString);
            bool allowed = IpWhitelistMiddleware.IsPrivateOrLocal(ip);
            Assert.Equal(expectedAllowed, allowed);
        }

        [Fact]
        public void AuthManager_GeneratesSixDigitPin()
        {
            var auth = new AuthManager();
            Assert.NotNull(auth.ServerPin);
            Assert.Equal(6, auth.ServerPin.Length);
            Assert.True(int.TryParse(auth.ServerPin, out int pinNum));
            Assert.InRange(pinNum, 100000, 999999);
        }

        [Fact]
        public void AuthManager_AuthenticatesOnlyWithCorrectPin()
        {
            var auth = new AuthManager("123456");
            var clientIp = IPAddress.Parse("192.168.1.50");

            var fail = auth.TryAuthenticate("999999", clientIp);
            Assert.Equal(AuthStatus.InvalidPin, fail.Status);
            Assert.Null(fail.Token);
            Assert.Contains("PIN incorrecto", fail.ErrorMessage);

            var ok = auth.TryAuthenticate("123456", clientIp);
            Assert.Equal(AuthStatus.Success, ok.Status);
            Assert.NotNull(ok.Token);
            Assert.NotEmpty(ok.Token);
            Assert.Null(ok.ErrorMessage);

            Assert.True(auth.ValidateToken(ok.Token));
            Assert.False(auth.ValidateToken("fake-token-xyz"));
        }

        [Fact]
        public void AuthManager_EnforcesPerIpRateLimitingAndLockout()
        {
            var auth = new AuthManager("654321");
            var ipA = IPAddress.Parse("192.168.1.10");
            var ipB = IPAddress.Parse("192.168.1.20");

            for (int i = 0; i < 4; i++)
            {
                var attempt = auth.TryAuthenticate("000000", ipA);
                Assert.Equal(AuthStatus.InvalidPin, attempt.Status);
                Assert.False(auth.IsLockedOut(ipA));
            }

            var fifth = auth.TryAuthenticate("000000", ipA);
            Assert.Equal(AuthStatus.InvalidPin, fifth.Status);
            Assert.Null(fifth.Token);
            Assert.True(auth.IsLockedOut(ipA));
            Assert.Contains("bloqueada temporalmente", fifth.ErrorMessage);

            var blocked = auth.TryAuthenticate("654321", ipA);
            Assert.Equal(AuthStatus.LockedOut, blocked.Status);
            Assert.True(blocked.RetryAfterSeconds > 0);
            Assert.Contains("bloqueado", blocked.ErrorMessage);

            Assert.False(auth.IsLockedOut(ipB));
            var otherIpOk = auth.TryAuthenticate("654321", ipB);
            Assert.Equal(AuthStatus.Success, otherIpOk.Status);
            Assert.NotNull(otherIpOk.Token);
        }

        [Fact]
        public void MapBinarySerializer_IsValidHeader_ValidatesVersionAndLength()
        {
            string tempDir = Path.Combine(Path.GetTempPath(), "AtsGpsHeaderTest_" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(tempDir);
            try
            {
                string validPath = Path.Combine(tempDir, "valid.bin");
                using (var fs = File.Create(validPath))
                {
                    MapBinarySerializer.Serialize(new MapGraph(), fs);
                }
                Assert.True(MapBinarySerializer.IsValidHeader(validPath, 3));
                Assert.False(MapBinarySerializer.IsValidHeader(validPath, 2));

                string wrongVersionPath = Path.Combine(tempDir, "wrong_ver.bin");
                byte[] bytes = File.ReadAllBytes(validPath);
                bytes[8] = 2;
                File.WriteAllBytes(wrongVersionPath, bytes);
                Assert.False(MapBinarySerializer.IsValidHeader(wrongVersionPath, 3));

                string shortPath = Path.Combine(tempDir, "short.bin");
                File.WriteAllBytes(shortPath, Encoding.ASCII.GetBytes("ATSMAP03\x03\x00\x00\x00"));
                Assert.False(MapBinarySerializer.IsValidHeader(shortPath, 3));

                Assert.False(MapBinarySerializer.IsValidHeader(Path.Combine(tempDir, "missing.bin"), 3));
            }
            finally
            {
                try { Directory.Delete(tempDir, true); } catch { }
            }
        }

        [Fact]
        public async Task ControlKey_WithoutToken_Returns401()
        {
            await using var server = await RealTestServer.StartAsync();

            using var content = new StringContent("{\"action\":\"horn\"}", Encoding.UTF8, "application/json");
            var response = await server.Client.PostAsync("/api/control/key", content);

            Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);

            var emptyResponse = await server.Client.PostAsync("/api/control/key", new StringContent(string.Empty));
            Assert.Equal(HttpStatusCode.Unauthorized, emptyResponse.StatusCode);
        }

        [Fact]
        public async Task MapEndpoints_WithoutToken_Return401()
        {
            await using var server = await RealTestServer.StartAsync();

            var infoResp = await server.Client.GetAsync("/api/map/info");
            Assert.Equal(HttpStatusCode.Unauthorized, infoResp.StatusCode);

            var downloadResp = await server.Client.GetAsync("/api/map/download");
            Assert.Equal(HttpStatusCode.Unauthorized, downloadResp.StatusCode);

            var buildResp = await server.Client.PostAsync("/api/map/build", new StringContent(string.Empty));
            Assert.Equal(HttpStatusCode.Unauthorized, buildResp.StatusCode);
        }

        [Fact]
        public async Task Destinations_WithoutToken_Returns401()
        {
            await using var server = await RealTestServer.StartAsync();

            var response = await server.Client.GetAsync("/api/destinations");
            Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        }

        [Fact]
        public async Task TelemetrySnapshotAndWebSocket_WithoutToken_Return401()
        {
            await using var server = await RealTestServer.StartAsync();

            var snapshotResp = await server.Client.GetAsync("/api/telemetry/snapshot");
            Assert.Equal(HttpStatusCode.Unauthorized, snapshotResp.StatusCode);

            var wsHttpResp = await server.Client.GetAsync("/ws/telemetry");
            Assert.Equal(HttpStatusCode.Unauthorized, wsHttpResp.StatusCode);

            using var ws = new ClientWebSocket();
            var wsUri = new Uri($"ws://127.0.0.1:{server.BaseUri.Port}/ws/telemetry");
            await Assert.ThrowsAsync<WebSocketException>(() => ws.ConnectAsync(wsUri, CancellationToken.None));
        }

        [Theory]
        [InlineData("https://evil.com")]
        [InlineData("http://localhost:35500")]
        [InlineData("null")]
        [InlineData("")]
        public async Task AnyRequest_WithOriginHeader_Returns403(string originValue)
        {
            await using var server = await RealTestServer.StartAsync();

            using var req = new HttpRequestMessage(HttpMethod.Get, "/api/status");
            req.Headers.TryAddWithoutValidation("Origin", originValue);

            var response = await server.Client.SendAsync(req);
            Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        }

        [Theory]
        [InlineData("evil.com", HttpStatusCode.Forbidden)]
        [InlineData("8.8.8.8:35500", HttpStatusCode.Forbidden)]
        [InlineData("attacker.local", HttpStatusCode.Forbidden)]
        [InlineData("169.254.1.10:35500", HttpStatusCode.Forbidden)]
        [InlineData("localhost:35500", HttpStatusCode.OK)]
        [InlineData("127.0.0.1:35500", HttpStatusCode.OK)]
        [InlineData("192.168.1.10:35500", HttpStatusCode.OK)]
        [InlineData("10.0.0.2:35500", HttpStatusCode.OK)]
        [InlineData("172.16.5.4:35500", HttpStatusCode.OK)]
        [InlineData("[::1]:35500", HttpStatusCode.OK)]
        [InlineData("[fe80::1]:35500", HttpStatusCode.OK)]
        [InlineData("[fc00::1]:35500", HttpStatusCode.OK)]
        public async Task HostHeaderValidation_EnforcesLocalAndPrivateHosts(string hostHeader, HttpStatusCode expectedStatus)
        {
            await using var server = await RealTestServer.StartAsync();

            using var req = new HttpRequestMessage(HttpMethod.Get, "/api/status");
            req.Headers.Host = hostHeader;

            var response = await server.Client.SendAsync(req);
            Assert.Equal(expectedStatus, response.StatusCode);
        }

        [Theory]
        [InlineData("127.0.0.1", "USB_DIRECT")]
        [InlineData("127.0.0.1", "AUTO_USB")]
        [InlineData("127.0.0.1", "")]
        [InlineData("127.0.0.1", "000000")]
        [InlineData("192.168.42.129", "USB_DIRECT")]
        [InlineData("192.168.42.129", "AUTO_USB")]
        [InlineData("192.168.42.129", "")]
        [InlineData("192.168.42.129", "000000")]
        public async Task Pair_RejectsUsbExemptionsAndInvalidPinsFromLoopbackAndUsbTethering(string clientIp, string pin)
        {
            await using var server = await RealTestServer.StartAsync();

            using var req = new HttpRequestMessage(HttpMethod.Post, "/api/pair");
            req.Headers.TryAddWithoutValidation("X-Test-Remote-Ip", clientIp);
            req.Content = new StringContent(JsonSerializer.Serialize(new { pin }), Encoding.UTF8, "application/json");

            var response = await server.Client.SendAsync(req);
            Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);

            string body = await response.Content.ReadAsStringAsync();
            using var doc = JsonDocument.Parse(body);
            Assert.False(doc.RootElement.TryGetProperty("token", out _));
        }

        [Fact]
        public async Task Pair_WithRealPin_IssuesToken_AndAuthorizesProtectedEndpointsAndWebSocket()
        {
            await using var server = await RealTestServer.StartAsync(TestPin);

            using var pairReq = new HttpRequestMessage(HttpMethod.Post, "/api/pair");
            pairReq.Content = new StringContent(JsonSerializer.Serialize(new { pin = TestPin }), Encoding.UTF8, "application/json");
            var pairResp = await server.Client.SendAsync(pairReq);
            Assert.Equal(HttpStatusCode.OK, pairResp.StatusCode);

            string pairJson = await pairResp.Content.ReadAsStringAsync();
            using var pairDoc = JsonDocument.Parse(pairJson);
            string? token = pairDoc.RootElement.GetProperty("token").GetString();
            Assert.False(string.IsNullOrWhiteSpace(token));
            Assert.Equal(48, pairDoc.RootElement.GetProperty("expiresInHours").GetInt32());

            server.Client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);

            var mapInfoResp = await server.Client.GetAsync("/api/map/info");
            Assert.Equal(HttpStatusCode.OK, mapInfoResp.StatusCode);

            var destResp = await server.Client.GetAsync("/api/destinations");
            Assert.Equal(HttpStatusCode.OK, destResp.StatusCode);

            var snapResp = await server.Client.GetAsync("/api/telemetry/snapshot");
            Assert.Equal(HttpStatusCode.OK, snapResp.StatusCode);

            using var keyContent = new StringContent("{\"action\":\"horn\"}", Encoding.UTF8, "application/json");
            var keyResp = await server.Client.PostAsync("/api/control/key", keyContent);
            Assert.NotEqual(HttpStatusCode.Unauthorized, keyResp.StatusCode);
            Assert.Equal(HttpStatusCode.OK, keyResp.StatusCode);

            using var ws = new ClientWebSocket();
            ws.Options.SetRequestHeader("Authorization", $"Bearer {token}");
            var wsUri = new Uri($"ws://127.0.0.1:{server.BaseUri.Port}/ws/telemetry");
            await ws.ConnectAsync(wsUri, CancellationToken.None);
            Assert.Equal(WebSocketState.Open, ws.State);
            await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "Test done", CancellationToken.None);
        }

        [Fact]
        public async Task Pair_LockoutIsPerIp_BlocksAttackerWith429WhileOtherIpAuthenticates()
        {
            await using var server = await RealTestServer.StartAsync(TestPin);

            const string attackerIp = "192.168.1.10";
            const string legitimateIp = "192.168.1.20";

            // 5 fallos desde attackerIp
            for (int i = 0; i < AuthManager.MaxFailedAttempts; i++)
            {
                using var failReq = new HttpRequestMessage(HttpMethod.Post, "/api/pair");
                failReq.Headers.TryAddWithoutValidation("X-Test-Remote-Ip", attackerIp);
                failReq.Content = new StringContent("{\"pin\":\"000000\"}", Encoding.UTF8, "application/json");
                var failResp = await server.Client.SendAsync(failReq);
                Assert.Equal(HttpStatusCode.Unauthorized, failResp.StatusCode);
            }

            // Siguiente intento desde attackerIp (incluso con PIN correcto) -> 429 Too Many Requests
            using var blockedReq = new HttpRequestMessage(HttpMethod.Post, "/api/pair");
            blockedReq.Headers.TryAddWithoutValidation("X-Test-Remote-Ip", attackerIp);
            blockedReq.Content = new StringContent(JsonSerializer.Serialize(new { pin = TestPin }), Encoding.UTF8, "application/json");
            var blockedResp = await server.Client.SendAsync(blockedReq);
            Assert.Equal(HttpStatusCode.TooManyRequests, blockedResp.StatusCode);
            Assert.True(blockedResp.Headers.Contains("Retry-After"));

            // legitimateIp debe poder autenticarse normalmente con el PIN correcto -> 200 OK
            using var validReq = new HttpRequestMessage(HttpMethod.Post, "/api/pair");
            validReq.Headers.TryAddWithoutValidation("X-Test-Remote-Ip", legitimateIp);
            validReq.Content = new StringContent(JsonSerializer.Serialize(new { pin = TestPin }), Encoding.UTF8, "application/json");
            var validResp = await server.Client.SendAsync(validReq);
            Assert.Equal(HttpStatusCode.OK, validResp.StatusCode);
        }
    }
}
