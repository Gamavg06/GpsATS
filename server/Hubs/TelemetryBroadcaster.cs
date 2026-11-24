using System;
using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using AtsGpsServer.Map;
using AtsGpsServer.Security;
using AtsGpsServer.Telemetry;

namespace AtsGpsServer.Hubs
{
    public class TelemetryBroadcaster : IDisposable
    {
        private sealed class ClientConnection
        {
            private volatile bool _closed;

            public WebSocket Socket { get; }
            public SemaphoreSlim SendLock { get; } = new(1, 1);
            public bool IsClosed => _closed;

            public ClientConnection(WebSocket socket)
            {
                Socket = socket;
            }

            public void MarkClosed() => _closed = true;
        }

        private readonly ScsTelemetryReader _reader;
        private readonly AuthManager _auth;
        private readonly DestinationResolver _resolver;
        private readonly ConcurrentDictionary<Guid, ClientConnection> _sockets = new();
        private readonly CancellationTokenSource _cts = new();
        private int _stopped;

        public int ConnectedClientsCount => _sockets.Count;

        public TelemetryBroadcaster(ScsTelemetryReader reader, AuthManager auth, DestinationResolver resolver)
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
                connection.MarkClosed();

                bool lockTaken = false;
                try
                {
                    lockTaken = await connection.SendLock.WaitAsync(TimeSpan.FromSeconds(2));
                    if (socket.State == WebSocketState.Open)
                    {
                        await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Desconectado", CancellationToken.None);
                    }
                    else if (socket.State == WebSocketState.CloseReceived)
                    {
                        await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "Desconectado", CancellationToken.None);
                    }
                }
                catch
                {
                    // Ignorar errores al cerrar socket ya abortado
                }
                finally
                {
                    if (lockTaken)
                    {
                        connection.SendLock.Release();
                    }
                    connection.SendLock.Dispose();
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

            string? cachedCity = null;
            string? cachedCompany = null;
            (uint NodeIndex, float X, float Z)? cachedTarget = null;
            long lastNullResolveTick = 0;

            while (!token.IsCancellationRequested)
            {
                try
                {
                    if (!_sockets.IsEmpty)
                    {
                        var snapshot = _reader.ReadSnapshot();
                        if (!string.IsNullOrEmpty(snapshot.DestinationCity))
                        {
                            bool jobChanged =
                                !string.Equals(cachedCity, snapshot.DestinationCity, StringComparison.Ordinal) ||
                                !string.Equals(cachedCompany, snapshot.DestinationCompany, StringComparison.Ordinal);

                            if (jobChanged || (cachedTarget == null && Environment.TickCount64 - lastNullResolveTick >= 2000))
                            {
                                cachedCity = snapshot.DestinationCity;
                                cachedCompany = snapshot.DestinationCompany;
                                cachedTarget = _resolver.ResolveJobDestination(cachedCity, cachedCompany);
                                lastNullResolveTick = cachedTarget == null ? Environment.TickCount64 : 0;
                            }

                            if (cachedTarget != null)
                            {
                                snapshot.TargetNodeIndex = cachedTarget.Value.NodeIndex;
                                snapshot.TargetPosX = ScsTelemetryReader.SanitizeFloat(cachedTarget.Value.X);
                                snapshot.TargetPosZ = ScsTelemetryReader.SanitizeFloat(cachedTarget.Value.Z);
                            }
                        }
                        else if (cachedCity != null || cachedCompany != null || cachedTarget != null)
                        {
                            cachedCity = null;
                            cachedCompany = null;
                            cachedTarget = null;
                            lastNullResolveTick = 0;
                        }

                        byte[] jsonBytes = JsonSerializer.SerializeToUtf8Bytes(snapshot, options);
                        var segment = new ArraySegment<byte>(jsonBytes);

                        foreach (var kvp in _sockets)
                        {
                            var client = kvp.Value;
                            if (!client.IsClosed && client.Socket.State == WebSocketState.Open)
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
            if (client.IsClosed)
            {
                return;
            }

            bool lockTaken = false;
            try
            {
                lockTaken = client.SendLock.Wait(0);
                if (!lockTaken)
                {
                    return;
                }

                if (!client.IsClosed && client.Socket.State == WebSocketState.Open && !token.IsCancellationRequested)
                {
                    await client.Socket.SendAsync(segment, WebSocketMessageType.Text, true, token);
                }
            }
            catch
            {
                // Ignorar error si el cliente cerro el socket durante el envio
            }
            finally
            {
                if (lockTaken)
                {
                    try
                    {
                        client.SendLock.Release();
                    }
                    catch (ObjectDisposedException)
                    {
                    }
                }
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
