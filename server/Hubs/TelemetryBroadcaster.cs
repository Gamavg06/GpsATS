using System;
using System.Collections.Concurrent;
using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using AtsGpsServer.Telemetry;
using AtsGpsServer.Security;

namespace AtsGpsServer.Hubs
{
    public class TelemetryBroadcaster
    {
        private readonly ScsTelemetryReader _reader;
        private readonly AuthManager _auth;
        private readonly AtsGpsServer.Map.DestinationResolver _resolver;
        private readonly ConcurrentDictionary<Guid, WebSocket> _sockets = new();
        private readonly CancellationTokenSource _cts = new();

        public int ConnectedClientsCount => _sockets.Count;

        public TelemetryBroadcaster(ScsTelemetryReader reader, AuthManager auth, AtsGpsServer.Map.DestinationResolver resolver)
        {
            _reader = reader;
            _auth = auth;
            _resolver = resolver;
            Task.Run(BroadcastLoop);
        }

        public async Task HandleWebSocketAsync(WebSocket socket, string? token)
        {
            if (!_auth.ValidateToken(token))
            {
                await socket.CloseAsync(WebSocketCloseStatus.PolicyViolation, "Token no autorizado", CancellationToken.None);
                return;
            }

            var id = Guid.NewGuid();
            _sockets[id] = socket;

            var buffer = new byte[1024];
            try
            {
                while (socket.State == WebSocketState.Open && !_cts.Token.IsCancellationRequested)
                {
                    var result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), _cts.Token);
                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        break;
                    }
                }
            }
            catch
            {
                // Desconexión normal del cliente
            }
            finally
            {
                _sockets.TryRemove(id, out _);
                if (socket.State == WebSocketState.Open)
                {
                    await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Desconectado", CancellationToken.None);
                }
            }
        }

        private async Task BroadcastLoop()
        {
            var options = new JsonSerializerOptions
            {
                PropertyNamingPolicy = JsonNamingPolicy.CamelCase
            };

            while (!_cts.Token.IsCancellationRequested)
            {
                try
                {
                    if (!_sockets.IsEmpty)
                    {
                        var snapshot = _reader.ReadSnapshot();
                        if (!string.IsNullOrEmpty(snapshot.DestinationCity))
                        {
                            var target = _resolver.ResolveJobDestination(snapshot.DestinationCity, snapshot.DestinationCompany);
                            if (target != null)
                            {
                                snapshot.TargetNodeIndex = target.Value.NodeIndex;
                                snapshot.TargetPosX = target.Value.X;
                                snapshot.TargetPosZ = target.Value.Z;
                            }
                        }

                        byte[] jsonBytes = JsonSerializer.SerializeToUtf8Bytes(snapshot, options);
                        var segment = new ArraySegment<byte>(jsonBytes);

                        foreach (var kvp in _sockets)
                        {
                            var client = kvp.Value;
                            if (client.State == WebSocketState.Open)
                            {
                                _ = client.SendAsync(segment, WebSocketMessageType.Text, true, CancellationToken.None);
                            }
                        }
                    }

                    // 30 Hz = 33 milisegundos
                    await Task.Delay(33, _cts.Token);
                }
                catch (TaskCanceledException)
                {
                    break;
                }
                catch (Exception)
                {
                    await Task.Delay(100, _cts.Token);
                }
            }
        }

        public void Stop()
        {
            _cts.Cancel();
        }
    }
}
