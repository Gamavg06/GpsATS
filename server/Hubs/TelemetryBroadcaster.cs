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
    public class TelemetryBroadcaster : IDisposable
    {
        private sealed class ClientConnection
        {
            public WebSocket Socket { get; }
            public SemaphoreSlim SendLock { get; } = new(1, 1);

            public ClientConnection(WebSocket socket)
            {
                Socket = socket;
            }
        }

        private readonly ScsTelemetryReader _reader;
        private readonly AuthManager _auth;
        private readonly AtsGpsServer.Map.DestinationResolver _resolver;
        private readonly ConcurrentDictionary<Guid, ClientConnection> _sockets = new();
        private readonly CancellationTokenSource _cts = new();
        private int _stopped;

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
            var connection = new ClientConnection(socket);
            _sockets[id] = connection;

            var buffer = new byte[1024];
            try
            {
                while (socket.State == WebSocketState.Open && !_cts.Token.IsCancellationRequested)
                {
                    var result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), _cts.Token);
                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        if (socket.State == WebSocketState.CloseReceived)
                        {
                            await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "Desconectado", CancellationToken.None);
                        }
                        break;
                    }
                }
            }
            catch
            {
                // Desconexion normal del cliente
            }
            finally
            {
                _sockets.TryRemove(id, out _);
                if (socket.State == WebSocketState.Open)
                {
                    try
                    {
                        await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Desconectado", CancellationToken.None);
                    }
                    catch { }
                }
                else if (socket.State == WebSocketState.CloseReceived)
                {
                    try
                    {
                        await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "Desconectado", CancellationToken.None);
                    }
                    catch { }
                }
            }
        }

        private async Task BroadcastLoop()
        {
            var token = _cts.Token;
            var options = new JsonSerializerOptions
            {
                PropertyNamingPolicy = JsonNamingPolicy.CamelCase
            };

            while (!token.IsCancellationRequested)
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
                            if (client.Socket.State == WebSocketState.Open)
                            {
                                _ = SendSafeAsync(client, segment, token);
                            }
                        }
                    }

                    // 30 Hz = 33 milisegundos
                    await Task.Delay(33, token);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception)
                {
                    try
                    {
                        await Task.Delay(100, token);
                    }
                    catch (OperationCanceledException)
                    {
                        break;
                    }
                }
            }
        }

        private static async Task SendSafeAsync(ClientConnection client, ArraySegment<byte> segment, CancellationToken token)
        {
            if (!client.SendLock.Wait(0))
            {
                return;
            }

            try
            {
                if (client.Socket.State == WebSocketState.Open && !token.IsCancellationRequested)
                {
                    await client.Socket.SendAsync(segment, WebSocketMessageType.Text, true, token);
                }
            }
            catch
            {
                // Ignorar error si el cliente cerró el socket durante el envío
            }
            finally
            {
                client.SendLock.Release();
            }
        }

        public void Stop()
        {
            if (Interlocked.Exchange(ref _stopped, 1) == 0)
            {
                _cts.Cancel();
            }
        }

        public void Dispose()
        {
            Stop();
            _cts.Dispose();
        }
    }
}
