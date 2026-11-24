using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Text.Json;
using System.Threading.Tasks;
using AtsGpsServer.Hubs;
using AtsGpsServer.Input;
using AtsGpsServer.Map;
using AtsGpsServer.Security;
using AtsGpsServer.Telemetry;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace AtsGpsServer
{
    public static class AtsServerBuilder
    {
        public static WebApplication CreateWebApplication(
            string[]? args = null,
            int port = 35500,
            IPAddress? listenAddress = null,
            string? fixedPin = null,
            Action<WebApplication>? configurePreSecurity = null,
            bool suppressConsoleLogs = false)
        {
            var builder = WebApplication.CreateBuilder(args ?? Array.Empty<string>());

            if (suppressConsoleLogs)
            {
                builder.Logging.ClearProviders();
            }

            builder.WebHost.ConfigureKestrel(options =>
            {
                options.Listen(listenAddress ?? IPAddress.Any, port);
            });

            builder.Services.AddSingleton<ScsTelemetryReader>();
            if (fixedPin != null)
            {
                builder.Services.AddSingleton(new AuthManager(fixedPin));
            }
            else
            {
                builder.Services.AddSingleton<AuthManager>();
            }
            builder.Services.AddSingleton<MapManager>();
            builder.Services.AddSingleton<DestinationResolver>();
            builder.Services.AddSingleton<TelemetryBroadcaster>();
            builder.Services.AddSingleton<VirtualInputController>();

            var app = builder.Build();

            // Hook opcional solo para inyeccion de IP simulada en pruebas unitarias/integracion
            configurePreSecurity?.Invoke(app);

            // Seguridad al inicio del pipeline: IP local/privada, bloqueo de Origin y validacion estricta de Host
            app.UseMiddleware<IpWhitelistMiddleware>();
            app.UseMiddleware<RequestSecurityMiddleware>();

            app.UseWebSockets(new WebSocketOptions
            {
                KeepAliveInterval = TimeSpan.FromSeconds(15)
            });

            ConfigureEndpoints(app);

            app.Lifetime.ApplicationStopping.Register(() =>
            {
                app.Services.GetRequiredService<TelemetryBroadcaster>().Stop();
            });

            return app;
        }

        private static void ConfigureEndpoints(WebApplication app)
        {
            // 1. Estado del Servidor (publico en LAN: salud y version)
            app.MapGet("/api/status", (ScsTelemetryReader reader, TelemetryBroadcaster broadcaster) =>
            {
                return Results.Ok(new
                {
                    server = "ATS GPS Server v1.4.2 (.NET 10)",
                    atsConnected = reader.IsGameConnected,
                    connectedClients = broadcaster.ConnectedClientsCount,
                    serverTime = DateTimeOffset.UtcNow
                });
            });

            // 2. Emparejamiento obligatorio por PIN (sin exenciones USB ni localhost)
            app.MapPost("/api/pair", async (HttpContext context, AuthManager auth) =>
            {
                try
                {
                    using var reader = new StreamReader(context.Request.Body);
                    var body = await reader.ReadToEndAsync();

                    string? pin = null;
                    if (!string.IsNullOrWhiteSpace(body))
                    {
                        using var doc = JsonDocument.Parse(body);
                        if (doc.RootElement.ValueKind == JsonValueKind.Object &&
                            doc.RootElement.TryGetProperty("pin", out var pinProp) &&
                            pinProp.ValueKind == JsonValueKind.String)
                        {
                            pin = pinProp.GetString();
                        }
                    }

                    var result = auth.TryAuthenticate(pin, context.Connection.RemoteIpAddress);
                    if (result.Status == AuthStatus.Success && result.Token != null)
                    {
                        return Results.Ok(new
                        {
                            authenticated = true,
                            token = result.Token,
                            expiresInHours = 48,
                            message = "Dispositivo emparejado exitosamente con la PC."
                        });
                    }

                    if (result.Status == AuthStatus.LockedOut)
                    {
                        context.Response.Headers.RetryAfter = result.RetryAfterSeconds.ToString();
                        return Results.Json(
                            new
                            {
                                authenticated = false,
                                error = result.ErrorMessage,
                                retryAfterSeconds = result.RetryAfterSeconds
                            },
                            statusCode: StatusCodes.Status429TooManyRequests);
                    }

                    return Results.Json(
                        new
                        {
                            authenticated = false,
                            error = result.ErrorMessage ?? "Codigo PIN incorrecto."
                        },
                        statusCode: StatusCodes.Status401Unauthorized);
                }
                catch (JsonException ex)
                {
                    return Results.BadRequest(new { error = "JSON invalido: " + ex.Message });
                }
            });

            // 3. Snapshot puntual de telemetria (requiere token valido)
            app.MapGet("/api/telemetry/snapshot", (HttpContext context, ScsTelemetryReader reader, AuthManager auth) =>
            {
                if (!auth.ValidateToken(ExtractToken(context)))
                {
                    return UnauthorizedJson();
                }

                return Results.Ok(reader.ReadSnapshot());
            });

            // 4. WebSocket para transmision en tiempo real (validar token ANTES de AcceptWebSocketAsync)
            app.MapGet("/ws/telemetry", async (HttpContext context, AuthManager auth, TelemetryBroadcaster broadcaster) =>
            {
                string? token = ExtractToken(context, allowQueryFallback: true);
                if (!auth.ValidateToken(token))
                {
                    context.Response.StatusCode = StatusCodes.Status401Unauthorized;
                    context.Response.ContentType = "application/json";
                    await context.Response.WriteAsJsonAsync(new
                    {
                        error = "No autorizado. Proporcione un token valido tras emparejar con el PIN."
                    });
                    return;
                }

                if (!context.WebSockets.IsWebSocketRequest)
                {
                    context.Response.StatusCode = StatusCodes.Status400BadRequest;
                    return;
                }

                using var webSocket = await context.WebSockets.AcceptWebSocketAsync();
                await broadcaster.HandleWebSocketAsync(webSocket, token);
            });

            // 5. Informacion del grafo de mapa (requiere token valido)
            app.MapGet("/api/map/info", (HttpContext context, AuthManager auth, MapManager mapManager) =>
            {
                if (!auth.ValidateToken(ExtractToken(context)))
                {
                    return UnauthorizedJson();
                }

                return Results.Ok(mapManager.GetMapInfo());
            });

            // 6. Generar / Reconstruir mapa (requiere token valido)
            app.MapPost("/api/map/build", async (HttpContext context, AuthManager auth, MapManager mapManager) =>
            {
                if (!auth.ValidateToken(ExtractToken(context)))
                {
                    return UnauthorizedJson();
                }

                try
                {
                    var graph = await mapManager.BuildMapAsync((step, cur, tot) =>
                    {
                        Console.WriteLine($"[GENERADOR MAPA] ({cur}/{tot}) {step}");
                    });

                    return Results.Ok(new
                    {
                        success = true,
                        message = "Mapa generado exitosamente.",
                        nodes = graph.Nodes.Count,
                        edges = graph.Edges.Count,
                        cities = graph.Cities.Count,
                        companies = graph.Companies.Count
                    });
                }
                catch (Exception ex)
                {
                    return Results.Problem("Error generando el mapa: " + ex.Message);
                }
            });

            // 7. Descarga de archivo binario de mapa (requiere token valido)
            app.MapGet("/api/map/download", (HttpContext context, AuthManager auth, MapManager mapManager) =>
            {
                if (!auth.ValidateToken(ExtractToken(context)))
                {
                    return UnauthorizedJson();
                }

                if (!mapManager.IsMapReady)
                {
                    return Results.NotFound(new { error = "El mapa aun no ha sido generado. Ejecute POST /api/map/build primero." });
                }

                return Results.File(mapManager.MapFilePath, "application/octet-stream", "map_ats.bin");
            });

            // 8. Lista de destinos disponibles (requiere token valido)
            app.MapGet("/api/destinations", (HttpContext context, AuthManager auth, DestinationResolver resolver) =>
            {
                if (!auth.ValidateToken(ExtractToken(context)))
                {
                    return UnauthorizedJson();
                }

                return Results.Ok(resolver.GetAllSelectableDestinations());
            });

            // 9. Control interactivo de cabina (requiere token valido antes de leer cuerpo)
            app.MapPost("/api/control/key", async (HttpContext context, AuthManager auth, VirtualInputController inputCtrl) =>
            {
                if (!auth.ValidateToken(ExtractToken(context)))
                {
                    return UnauthorizedJson();
                }

                ControlKeyRequest? req;
                try
                {
                    req = await context.Request.ReadFromJsonAsync<ControlKeyRequest>();
                }
                catch (JsonException)
                {
                    return Results.BadRequest(new { success = false, error = "JSON invalido" });
                }

                if (string.IsNullOrWhiteSpace(req?.Action))
                {
                    return Results.BadRequest(new { success = false, error = "Accion no especificada" });
                }

                bool ok = inputCtrl.TriggerAction(req.Action);
                return Results.Ok(new { success = ok, action = req.Action });
            });
        }

        internal static string? ExtractToken(HttpContext context, bool allowQueryFallback = false)
        {
            var authHeader = context.Request.Headers["Authorization"].FirstOrDefault();
            if (authHeader != null && authHeader.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase))
            {
                string bearerToken = authHeader.Substring(7).Trim();
                if (!string.IsNullOrEmpty(bearerToken))
                {
                    return bearerToken;
                }
            }

            if (allowQueryFallback)
            {
                string? queryToken = context.Request.Query["token"].FirstOrDefault();
                if (!string.IsNullOrWhiteSpace(queryToken))
                {
                    return queryToken.Trim();
                }
            }

            return null;
        }

        private static IResult UnauthorizedJson()
        {
            return Results.Json(
                new { error = "No autorizado. Proporcione un token valido tras emparejar con el PIN." },
                statusCode: StatusCodes.Status401Unauthorized);
        }
    }

    public record ControlKeyRequest(string? Action);
}
