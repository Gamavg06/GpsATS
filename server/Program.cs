using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using AtsGpsServer;
using AtsGpsServer.Hubs;
using AtsGpsServer.Map;
using AtsGpsServer.Security;
using AtsGpsServer.Telemetry;

try { Console.OutputEncoding = Encoding.UTF8; } catch { }

if (args.Contains("--help") || args.Contains("-h"))
{
    Console.WriteLine("ATS GPS Server (.NET 10)");
    Console.WriteLine("Uso:");
    Console.WriteLine("  AtsGpsServer                      Inicia el servidor de telemetria y WebSocket");
    Console.WriteLine("  AtsGpsServer --ats-dir \"<ruta>\"   Usa la ruta de instalacion de ATS indicada");
    Console.WriteLine("  AtsGpsServer --build-map          Extrae el mapa completo desde los archivos SCS del juego local");
    Console.WriteLine("  AtsGpsServer --build-map --force  Fuerza la regeneracion de map_ats.bin aunque ya exista v3");
    return;
}

string? atsDirArg = AtsServerBuilder.ParseAtsDirArg(args);

if (args.Contains("--build-map") || args.Contains("-b"))
{
    bool forceRebuild = args.Contains("--force") || args.Contains("-f");
    Console.WriteLine("================================================================================");
    Console.WriteLine("       ATS GPS - EXTRACTOR LOCAL DE MAPA DESDE ARCHIVOS SCS                   ");
    Console.WriteLine("================================================================================");
    var mapMgr = new MapManager(atsDirArg);
    Console.WriteLine($"Directorio del juego: {mapMgr.AtsDirectory}");

    if (!forceRebuild && MapBinarySerializer.IsValidHeader(mapMgr.MapFilePath))
    {
        Console.WriteLine("[MAPA] map_ats.bin ya existe y coincide con la version del extractor (ATSMAP03 v3). Omitiendo regeneracion.");
        Console.WriteLine($"Archivo actual: {mapMgr.MapFilePath}");
        return;
    }

    Console.WriteLine("Extrayendo sectores dinamicamente...");
    var sw = System.Diagnostics.Stopwatch.StartNew();
    try
    {
        var graph = await mapMgr.BuildMapAsync((step, cur, tot) =>
        {
            if (cur % 25 == 0 || cur == tot)
            {
                Console.WriteLine($"  [{cur}/{tot}] {step}");
            }
        });
        sw.Stop();
        Console.WriteLine($"\n[EXITO] Extraccion completada en {sw.ElapsedMilliseconds / 1000.0:F1} s.");
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
        Console.WriteLine($"\n[ERROR] Fallo la extraccion: {ex.Message}");
        Console.ResetColor();
        Environment.ExitCode = 1;
    }
    return;
}

const int ServerPort = 35500;
var app = AtsServerBuilder.CreateWebApplication(args, port: ServerPort, atsDir: atsDirArg);

// =========================================================
// PANTALLA DE INICIO EN CONSOLA
// =========================================================
var authManager = app.Services.GetRequiredService<AuthManager>();
_ = app.Services.GetRequiredService<ScsTelemetryReader>();
_ = app.Services.GetRequiredService<TelemetryBroadcaster>();
var mapManagerInstance = app.Services.GetRequiredService<MapManager>();

try { Console.Clear(); } catch { }
Console.ForegroundColor = ConsoleColor.Cyan;
Console.WriteLine("================================================================================");
Console.WriteLine("          ATS GPS & TELEMETRY SERVER (PC COMPANION - .NET 10)                  ");
Console.WriteLine("================================================================================");
Console.ResetColor();

Console.WriteLine("\n[SEGURIDAD] Codigo PIN de Emparejamiento: ");
Console.ForegroundColor = ConsoleColor.Yellow;
Console.WriteLine("  ***********************************");
Console.WriteLine($"  *          PIN: {authManager.ServerPin}           *");
Console.WriteLine("  ***********************************");
Console.ResetColor();

Console.WriteLine("\n[RED LOCAL] Direcciones IP disponibles para conectar desde la App Android:");
foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
{
    if (ni.OperationalStatus != OperationalStatus.Up)
    {
        continue;
    }

    if (ni.NetworkInterfaceType != NetworkInterfaceType.Wireless80211 &&
        ni.NetworkInterfaceType != NetworkInterfaceType.Ethernet)
    {
        continue;
    }

    if (IsVirtualOrTunnelAdapter(ni.Name, ni.Description))
    {
        continue;
    }

    foreach (var ip in ni.GetIPProperties().UnicastAddresses)
    {
        if (ip.Address.AddressFamily == AddressFamily.InterNetwork &&
            !IPAddress.IsLoopback(ip.Address) &&
            !IsApipaAddress(ip.Address))
        {
            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine($"  -> http://{ip.Address}:{ServerPort} ({ni.Name})");
            Console.ResetColor();
        }
    }
}

Console.WriteLine($"  -> http://127.0.0.1:{ServerPort} (Localhost)");

Console.WriteLine("\n[CONEXION CABLE USB (BAJA LATENCIA <1 MS)]");
ConfigureUsbAdbReverse(ServerPort);

Console.WriteLine("\n[PLUGIN SDK] Verificando scs-telemetry.dll...");
string atsDir = mapManagerInstance.AtsDirectory;
bool atsDetected = IsAtsInstallationDetected(atsDir);
string atsPluginPath = Path.Combine(atsDir, "bin", "win_x64", "plugins", "scs-telemetry.dll");
if (File.Exists(atsPluginPath))
{
    Console.ForegroundColor = ConsoleColor.Green;
    Console.WriteLine("  [OK] Plugin scs-telemetry.dll instalado en la carpeta de 64 bits de ATS.");
}
else
{
    Console.ForegroundColor = ConsoleColor.Red;
    Console.WriteLine("  [AVISO] No se detecto scs-telemetry.dll en la carpeta del juego.");
}
Console.ResetColor();

Console.WriteLine("\n[FIREWALL] Si la app en el telefono no conecta, asegurate de permitir");
Console.WriteLine($"           el puerto {ServerPort} TCP en el Firewall de Windows:");
Console.WriteLine($"           powershell: New-NetFirewallRule -DisplayName \"ATS GPS Server\" -Direction Inbound -LocalPort {ServerPort} -Protocol TCP -Action Allow");

if (!MapBinarySerializer.IsValidHeader(mapManagerInstance.MapFilePath))
{
    if (atsDetected)
    {
        Console.ForegroundColor = ConsoleColor.Yellow;
        Console.WriteLine("\n[MAPA] No se detecto 'map_ats.bin' valido (ATSMAP03 v3).");
        Console.WriteLine($"       Iniciando generacion automatica en segundo plano desde: {atsDir}");
        Console.ResetColor();

        _ = RunBackgroundMapBuildAsync(mapManagerInstance);
    }
    else
    {
        Console.ForegroundColor = ConsoleColor.Yellow;
        Console.WriteLine("\n[AVISO DE MAPA] No se detecto 'map_ats.bin' valido ni instalacion automatica de ATS.");
        Console.WriteLine("                Para generar la red vial desde tu instalacion local de ATS, ejecuta:");
        Console.WriteLine("                AtsGpsServer --build-map");
        Console.ResetColor();
    }
}
else
{
    Console.ForegroundColor = ConsoleColor.Green;
    Console.WriteLine($"\n[MAPA] Archivo 'map_ats.bin' disponible (ATSMAP03 v3): {mapManagerInstance.MapFilePath}");
    Console.ResetColor();
}

Console.WriteLine("\n[CONEXION WEBSOCKET]");
Console.WriteLine("  Protocolo: ws:// autenticado con PIN y token Bearer (sin cifrado TLS, exclusivo para red local de confianza).");

Console.WriteLine("\n[ESTADO] Servidor en ejecucion. Esperando conexiones de la app movil...\n");

try
{
    app.Run();
}
catch (Exception ex) when (ex is IOException || ex is SocketException || ex.InnerException is SocketException)
{
    Console.ForegroundColor = ConsoleColor.Red;
    Console.WriteLine($"\n[ERROR] No se pudo iniciar el servidor en el puerto {ServerPort}: {ex.Message}");
    Console.WriteLine($"        Verifica que no exista otra instancia de AtsGpsServer u otro programa usando el puerto {ServerPort}.");
    Console.ResetColor();
    Environment.ExitCode = 1;
}

static async Task RunBackgroundMapBuildAsync(MapManager mapManagerInstance)
{
    try
    {
        var sw = System.Diagnostics.Stopwatch.StartNew();
        var graph = await mapManagerInstance.BuildMapAsync((step, cur, tot) =>
        {
            if (cur % 50 == 0 || cur == tot)
            {
                Console.WriteLine($"[GENERADOR MAPA] ({cur}/{tot}) {step}");
            }
        });
        sw.Stop();
        Console.WriteLine($"[MAPA] Generacion automatica completada en {sw.ElapsedMilliseconds / 1000.0:F1} s ({graph.Nodes.Count} nodos, {graph.Edges.Count} aristas).");
    }
    catch (Exception ex)
    {
        Console.WriteLine($"[MAPA] Error en generacion automatica en segundo plano: {ex.Message}");
    }
}

static bool IsAtsInstallationDetected(string atsDir)
{
    if (string.IsNullOrWhiteSpace(atsDir) || !Directory.Exists(atsDir))
    {
        return false;
    }

    return File.Exists(Path.Combine(atsDir, "base_map.scs")) ||
           File.Exists(Path.Combine(atsDir, "base.scs")) ||
           Directory.Exists(Path.Combine(atsDir, "bin"));
}

static string? FindAdbExecutable()
{
    string appDir = AppContext.BaseDirectory;
    string parentDir = Path.GetFullPath(Path.Combine(appDir, ".."));
    string rootDir = Path.GetFullPath(Path.Combine(appDir, "..", ".."));

    string[] localCandidates =
    {
        Path.Combine(appDir, "platform-tools", "adb.exe"),
        Path.Combine(parentDir, "platform-tools", "adb.exe"),
        Path.Combine(rootDir, "platform-tools", "adb.exe"),
        Path.Combine(Directory.GetCurrentDirectory(), "platform-tools", "adb.exe"),
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Android\Sdk\platform-tools\adb.exe")
    };

    foreach (string candidate in localCandidates)
    {
        if (File.Exists(candidate))
        {
            return candidate;
        }
    }

    var pathEnv = Environment.GetEnvironmentVariable("PATH") ?? string.Empty;
    foreach (var dir in pathEnv.Split(';', StringSplitOptions.RemoveEmptyEntries))
    {
        var candidate = Path.Combine(dir.Trim(), "adb.exe");
        if (File.Exists(candidate))
        {
            return candidate;
        }
    }

    return null;
}

static void ConfigureUsbAdbReverse(int port)
{
    string? adbPath = FindAdbExecutable();

    if (adbPath == null)
    {
        Console.ForegroundColor = ConsoleColor.Yellow;
        Console.WriteLine("  [AVISO ADB] No se encontro 'adb.exe' en platform-tools ni en PATH.");
        Console.WriteLine("              Para usar cable USB (adb reverse), descarga Android SDK Platform-Tools:");
        Console.WriteLine("              https://developer.android.com/tools/releases/platform-tools");
        Console.ResetColor();
        return;
    }

    try
    {
        var psi = new System.Diagnostics.ProcessStartInfo(adbPath, $"reverse tcp:{port} tcp:{port}")
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        using var proc = System.Diagnostics.Process.Start(psi);
        if (proc != null && proc.WaitForExit(2000) && proc.ExitCode == 0)
        {
            Console.ForegroundColor = ConsoleColor.Cyan;
            Console.WriteLine($"  [OK] Reenvio USB activo (adb reverse tcp:{port} tcp:{port})");
            Console.WriteLine("       En la app movil selecciona 'Cable USB' e ingresa el PIN mostrado en consola.");
            Console.ResetColor();
            return;
        }
    }
    catch { }

    Console.ForegroundColor = ConsoleColor.DarkCyan;
    Console.WriteLine("  -> Para conectar por Cable USB con latencia <1ms:");
    Console.WriteLine("     1. Activa 'Depuracion USB' en el celular o tableta.");
    Console.WriteLine($"     2. Ejecuta en consola: adb reverse tcp:{port} tcp:{port}");
    Console.WriteLine("     3. En la app movil selecciona 'Cable USB' e ingresa el PIN mostrado en consola.");
    Console.WriteLine("     4. O activa 'Compartir Internet por USB' (Tethering) e ingresa el PIN.");
    Console.ResetColor();
}

public partial class Program
{
    private static readonly string[] VirtualAdapterKeywords =
    {
        "WSL",
        "Hyper-V",
        "vEthernet",
        "VirtualBox",
        "VMware",
        "Tailscale",
        "ZeroTier",
        "TAP",
        "VPN",
        "Loopback",
        "Pseudo"
    };

    internal static bool IsVirtualOrTunnelAdapter(string? name, string? description)
    {
        foreach (string keyword in VirtualAdapterKeywords)
        {
            if ((!string.IsNullOrEmpty(name) && name.Contains(keyword, StringComparison.OrdinalIgnoreCase)) ||
                (!string.IsNullOrEmpty(description) && description.Contains(keyword, StringComparison.OrdinalIgnoreCase)))
            {
                return true;
            }
        }

        return false;
    }

    internal static bool IsApipaAddress(IPAddress ip)
    {
        if (ip.AddressFamily != AddressFamily.InterNetwork)
        {
            return false;
        }

        byte[] bytes = ip.GetAddressBytes();
        return bytes[0] == 169 && bytes[1] == 254;
    }
}
