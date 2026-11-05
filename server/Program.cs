using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.Json;
using AtsGpsServer.Hubs;
using AtsGpsServer.Security;
using AtsGpsServer.Telemetry;

if (args.Contains("--help") || args.Contains("-h"))
{
    Console.WriteLine("ATS GPS Server (.NET 10)");
    Console.WriteLine("Uso:");
    Console.WriteLine("  AtsGpsServer               Inicia el servidor de telemetría y WebSocket");
    Console.WriteLine("  AtsGpsServer --build-map   Extrae el mapa completo desde los archivos SCS del juego local");
    return;
}

if (args.Contains("--build-map") || args.Contains("-b"))
{
    Console.WriteLine("================================================================================");
    Console.WriteLine("       ATS GPS - EXTRACTOR LOCAL DE MAPA DESDE ARCHIVOS SCS                   ");
    Console.WriteLine("================================================================================");
    var mapMgr = new AtsGpsServer.Map.MapManager();
    Console.WriteLine($"Directorio del juego: {mapMgr.AtsDirectory}");
    Console.WriteLine("Extrayendo sectores dinámicamente...");
    var sw = System.Diagnostics.Stopwatch.StartNew();
    try
    {
        var graph = await mapMgr.BuildMapAsync((step, cur, tot) =>
        {
            if (cur % 25 == 0 || cur == tot)
                Console.WriteLine($"  [{cur}/{tot}] {step}");
        });
        sw.Stop();
        Console.WriteLine($"\n[ÉXITO] Extracción completada en {sw.ElapsedMilliseconds / 1000.0:F1} s.");
        Console.WriteLine($"  Nodos viales:    {graph.Nodes.Count}");
        Console.WriteLine($"  Aristas viales:  {graph.Edges.Count}");
        Console.WriteLine($"  Ciudades:        {graph.Cities.Count}");
        Console.WriteLine($"  Empresas:        {graph.Companies.Count}");
        Console.WriteLine($"  POIs:            {graph.Pois.Count}");
        Console.WriteLine($"\nArchivo guardado en: {mapMgr.MapFilePath}");
    }
    catch (Exception ex)
    {
        Console.ForegroundColor = ConsoleColor.Red;
        Console.WriteLine($"\n[ERROR] Falló la extracción: {ex.Message}");
        Console.ResetColor();
    }
    return;
}

var builder = WebApplication.CreateBuilder(args);

// Configurar Kestrel para escuchar en 0.0.0.0 puerto 35500
const int ServerPort = 35500;
builder.WebHost.ConfigureKestrel(options =>
{
    options.Listen(IPAddress.Any, ServerPort);
});

// Inyección de dependencias
builder.Services.AddSingleton<ScsTelemetryReader>();
builder.Services.AddSingleton<AuthManager>();
builder.Services.AddSingleton<AtsGpsServer.Map.MapManager>();
builder.Services.AddSingleton<AtsGpsServer.Map.DestinationResolver>();
builder.Services.AddSingleton<TelemetryBroadcaster>();

var app = builder.Build();

// WebSockets habilitados
app.UseWebSockets(new WebSocketOptions
{
    KeepAliveInterval = TimeSpan.FromSeconds(15)
});

// Middleware de seguridad: Restricción a IPs locales / privadas (RFC 1918)
app.UseMiddleware<IpWhitelistMiddleware>();

// =========================================================
// ENDPOINTS DE LA API
// =========================================================

// 1. Estado del Servidor
app.MapGet("/api/status", (ScsTelemetryReader reader, TelemetryBroadcaster broadcaster) =>
{
    return Results.Ok(new
    {
        server = "ATS GPS Server v1.0 (.NET 10)",
        atsConnected = reader.IsGameConnected,
        connectedClients = broadcaster.ConnectedClientsCount,
        serverTime = DateTimeOffset.UtcNow
    });
});

// 2. Emparejamiento por PIN
app.MapPost("/api/pair", async (HttpContext context, AuthManager auth) =>
{
    try
    {
        using var reader = new StreamReader(context.Request.Body);
        var body = await reader.ReadToEndAsync();
        var doc = JsonDocument.Parse(body);
        
        string? pin = null;
        if (doc.RootElement.TryGetProperty("pin", out var pinProp))
        {
            pin = pinProp.GetString();
        }

        if (string.IsNullOrWhiteSpace(pin))
        {
            return Results.BadRequest(new { error = "El campo 'pin' es requerido." });
        }

        string? token = auth.TryAuthenticate(pin);
        if (token != null)
        {
            return Results.Ok(new
            {
                authenticated = true,
                token = token,
                expiresInHours = 48,
                message = "Dispositivo emparejado exitosamente con la PC."
            });
        }

        return Results.Json(new { authenticated = false, error = "Código PIN incorrecto." }, statusCode: 401);
    }
    catch (Exception ex)
    {
        return Results.BadRequest(new { error = "JSON inválido: " + ex.Message });
    }
});

// 3. Snapshot puntual de telemetría (REST)
app.MapGet("/api/telemetry/snapshot", (HttpContext context, ScsTelemetryReader reader, AuthManager auth) =>
{
    string? token = context.Request.Query["token"].FirstOrDefault();
    if (string.IsNullOrEmpty(token))
    {
        var authHeader = context.Request.Headers["Authorization"].FirstOrDefault();
        if (authHeader != null && authHeader.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase))
        {
            token = authHeader.Substring(7).Trim();
        }
    }

    if (!auth.ValidateToken(token))
    {
        return Results.Json(new { error = "No autorizado. Proporcione un token válido tras emparejar con el PIN." }, statusCode: 401);
    }

    return Results.Ok(reader.ReadSnapshot());
});

// 4. WebSocket para transmisión en tiempo real (30 Hz)
app.Map("/ws/telemetry", async (HttpContext context, TelemetryBroadcaster broadcaster) =>
{
    if (context.WebSockets.IsWebSocketRequest)
    {
        string? token = context.Request.Query["token"].FirstOrDefault();
        using var webSocket = await context.WebSockets.AcceptWebSocketAsync();
        await broadcaster.HandleWebSocketAsync(webSocket, token);
    }
    else
    {
        context.Response.StatusCode = (int)HttpStatusCode.BadRequest;
    }
});

// 5. Información del Grafo de Mapa
app.MapGet("/api/map/info", (AtsGpsServer.Map.MapManager mapManager) =>
{
    return Results.Ok(mapManager.GetMapInfo());
});

// 6. Generar / Reconstruir Mapa
app.MapPost("/api/map/build", async (AtsGpsServer.Map.MapManager mapManager) =>
{
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

// 7. Descarga de Archivo Binario de Mapa para la App Android
app.MapGet("/api/map/download", (AtsGpsServer.Map.MapManager mapManager) =>
{
    if (!mapManager.IsMapReady)
    {
        return Results.NotFound(new { error = "El mapa aún no ha sido generado. Ejecute POST /api/map/build primero." });
    }

    return Results.File(mapManager.MapFilePath, "application/octet-stream", "map_ats.bin");
});

// 8. Lista de Destinos Disponibles (Ciudades y Empresas) para Selector en App
app.MapGet("/api/destinations", (AtsGpsServer.Map.DestinationResolver resolver) =>
{
    return Results.Ok(resolver.GetAllSelectableDestinations());
});

// =========================================================
// PANTALLA DE INICIO EN CONSOLA
// =========================================================
var authManager = app.Services.GetRequiredService<AuthManager>();
var telemetryReader = app.Services.GetRequiredService<ScsTelemetryReader>();

try { Console.Clear(); } catch { }
Console.ForegroundColor = ConsoleColor.Cyan;
Console.WriteLine("================================================================================");
Console.WriteLine("          ATS GPS & TELEMETRY SERVER (PC COMPANION - .NET 10)                  ");
Console.WriteLine("================================================================================");
Console.ResetColor();

Console.WriteLine($"\n[SEGURIDAD] Código PIN de Emparejamiento: ");
Console.ForegroundColor = ConsoleColor.Yellow;
Console.WriteLine($"  ***********************************");
Console.WriteLine($"  *          PIN: {authManager.ServerPin}           *");
Console.WriteLine($"  ***********************************");
Console.ResetColor();

Console.WriteLine("\n[RED LOCAL] Direcciones IP disponibles para conectar desde la App Android:");
foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
{
    if (ni.OperationalStatus == OperationalStatus.Up && 
        (ni.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 || ni.NetworkInterfaceType == NetworkInterfaceType.Ethernet))
    {
        foreach (var ip in ni.GetIPProperties().UnicastAddresses)
        {
            if (ip.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip.Address))
            {
                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine($"  -> http://{ip.Address}:{ServerPort} ({ni.Name})");
                Console.ResetColor();
            }
        }
    }
}

Console.WriteLine($"  -> http://127.0.0.1:{ServerPort} (Localhost)");

Console.WriteLine("\n[PLUGIN SDK] Verificando scs-telemetry.dll...");
string atsPluginPath = @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator\bin\win_x64\plugins\scs-telemetry.dll";
if (File.Exists(atsPluginPath))
{
    Console.ForegroundColor = ConsoleColor.Green;
    Console.WriteLine("  [OK] Plugin scs-telemetry.dll instalado en la carpeta de 64 bits de ATS.");
}
else
{
    Console.ForegroundColor = ConsoleColor.Red;
    Console.WriteLine("  [AVISO] No se detectó scs-telemetry.dll en la carpeta del juego.");
}
Console.ResetColor();

Console.WriteLine("\n[FIREWALL] Si la app en el teléfono no conecta, asegúrate de permitir");
Console.WriteLine($"           el puerto {ServerPort} TCP en el Firewall de Windows:");
Console.WriteLine($"           powershell: New-NetFirewallRule -DisplayName \"ATS GPS Server\" -Direction Inbound -LocalPort {ServerPort} -Protocol TCP -Action Allow");

var mapManagerInstance = app.Services.GetRequiredService<AtsGpsServer.Map.MapManager>();
if (!mapManagerInstance.IsMapReady)
{
    Console.ForegroundColor = ConsoleColor.Yellow;
    Console.WriteLine("\n[AVISO DE MAPA] No se detectó 'map_ats.bin'.");
    Console.WriteLine("                Para generar la red vial desde tu instalación local de ATS, ejecuta:");
    Console.WriteLine("                AtsGpsServer --build-map");
    Console.ResetColor();
}
else
{
    Console.ForegroundColor = ConsoleColor.Green;
    Console.WriteLine($"\n[MAPA] Archivo 'map_ats.bin' disponible: {mapManagerInstance.MapFilePath}");
    Console.ResetColor();
}

Console.WriteLine("\n[CONEXIÓN WEBSOCKET]");
Console.WriteLine("  Protocolo: ws:// autenticado con PIN (sin cifrado TLS, exclusivo para red local de confianza).");

Console.WriteLine("\n[ESTADO] Servidor en ejecución. Esperando conexiones de la app móvil...\n");

app.Run();
