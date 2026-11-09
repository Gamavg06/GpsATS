using System;
using System.Diagnostics;
using System.IO;
using System.Security.Principal;

namespace AtsGpsLauncher;

internal class Program
{
    private const int ServerPort = 35500;

    static void Main(string[] args)
    {
        Console.Title = "ATS GPS - Servidor Companion";
        
        // Establecer directorio raíz C:\Telemetriagps
        string rootDir = AppDomain.CurrentDomain.BaseDirectory;
        if (!File.Exists(Path.Combine(rootDir, "server", "AtsGpsServer.csproj")))
        {
            if (Directory.Exists(@"C:\Telemetriagps"))
            {
                rootDir = @"C:\Telemetriagps";
            }
        }
        Environment.CurrentDirectory = rootDir;

        Console.ForegroundColor = ConsoleColor.Cyan;
        Console.WriteLine("================================================================================");
        Console.WriteLine("          ATS GPS - SERVIDOR COMPANION (.EXE)                                  ");
        Console.WriteLine("================================================================================");
        Console.ResetColor();
        Console.WriteLine();

        // 1. Detección de ATS y copia automática de scs-telemetry.dll
        DetectAtsAndCheckPlugin(rootDir);

        // 2. Configurar túnel USB ADB automáticamente
        ConfigureAdbReverse();

        // 3. Regla de Firewall (si tiene permisos de admin)
        ConfigureFirewall();

        // 4. Iniciar AtsGpsServer.exe
        string serverExe = Path.Combine(rootDir, "server", "publish", "AtsGpsServer.exe");
        if (!File.Exists(serverExe))
        {
            serverExe = Path.Combine(rootDir, "server", "bin", "Release", "net10.0-windows", "AtsGpsServer.exe");
        }

        if (!File.Exists(serverExe))
        {
            Console.ForegroundColor = ConsoleColor.Yellow;
            Console.WriteLine("[COMPILANDO] Compilando AtsGpsServer...");
            Console.ResetColor();
            var build = Process.Start(new ProcessStartInfo("dotnet", "publish -c Release -o \"server\\publish\" \"server\\AtsGpsServer.csproj\"")
            {
                WorkingDirectory = rootDir,
                UseShellExecute = false
            });
            build?.WaitForExit();
            serverExe = Path.Combine(rootDir, "server", "publish", "AtsGpsServer.exe");
        }

        if (!File.Exists(serverExe))
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine("[ERROR] No se encontró AtsGpsServer.exe ni se pudo compilar.");
            Console.ResetColor();
            Console.WriteLine("\nPresiona cualquier tecla para salir...");
            Console.ReadKey();
            return;
        }

        Console.ForegroundColor = ConsoleColor.Green;
        Console.WriteLine("[INICIO] Iniciando servidor companion en puerto 35500...\n");
        Console.ResetColor();

        var psi = new ProcessStartInfo(serverExe)
        {
            WorkingDirectory = rootDir,
            UseShellExecute = false
        };

        try
        {
            using var proc = Process.Start(psi);
            proc?.WaitForExit();
        }
        catch (Exception ex)
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine($"[ERROR] Error al ejecutar el servidor: {ex.Message}");
            Console.ResetColor();
            Console.WriteLine("\nPresiona cualquier tecla para salir...");
            Console.ReadKey();
        }
    }

    private static void DetectAtsAndCheckPlugin(string rootDir)
    {
        string defaultAts = @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
        string pluginTarget = Path.Combine(defaultAts, "bin", "win_x64", "plugins", "scs-telemetry.dll");
        string pluginSource = Path.Combine(rootDir, "server", "plugins", "scs-telemetry.dll");

        if (Directory.Exists(defaultAts))
        {
            if (File.Exists(pluginTarget))
            {
                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine("[OK] Plugin de telemetría detectado en ATS.");
            }
            else
            {
                if (File.Exists(pluginSource))
                {
                    try
                    {
                        string dir = Path.GetDirectoryName(pluginTarget)!;
                        if (!Directory.Exists(dir)) Directory.CreateDirectory(dir);
                        File.Copy(pluginSource, pluginTarget, true);
                        Console.ForegroundColor = ConsoleColor.Green;
                        Console.WriteLine("[AUTO-INSTALACIÓN] scs-telemetry.dll copiado a la carpeta de plugins de ATS.");
                    }
                    catch
                    {
                        Console.ForegroundColor = ConsoleColor.Yellow;
                        Console.WriteLine("[AVISO] Para habilitar la telemetría, copia 'server\\plugins\\scs-telemetry.dll' a:");
                        Console.WriteLine($"        {pluginTarget}");
                    }
                }
            }
        }
        Console.ResetColor();
    }

    private static void ConfigureAdbReverse()
    {
        string? adbPath = null;
        string defaultSdkAdb = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Android\Sdk\platform-tools\adb.exe");
        if (File.Exists(defaultSdkAdb))
        {
            adbPath = defaultSdkAdb;
        }
        else
        {
            var pathEnv = Environment.GetEnvironmentVariable("PATH") ?? "";
            foreach (var dir in pathEnv.Split(';', StringSplitOptions.RemoveEmptyEntries))
            {
                var candidate = Path.Combine(dir, "adb.exe");
                if (File.Exists(candidate))
                {
                    adbPath = candidate;
                    break;
                }
            }
        }

        if (adbPath != null)
        {
            try
            {
                var psi = new ProcessStartInfo(adbPath, $"reverse tcp:{ServerPort} tcp:{ServerPort}")
                {
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    UseShellExecute = false,
                    CreateNoWindow = true
                };
                using var proc = Process.Start(psi);
                proc?.WaitForExit(1500);

                if (proc?.ExitCode == 0)
                {
                    Console.ForegroundColor = ConsoleColor.Cyan;
                    Console.WriteLine("[CABLE USB] Puente ADB activo (tcp:35500 vinculado por cable).");
                    Console.WriteLine("            El teléfono conectará al instante en 127.0.0.1 sin PIN.");
                    Console.ResetColor();
                    return;
                }
            }
            catch { }
        }

        Console.ForegroundColor = ConsoleColor.DarkGray;
        Console.WriteLine("[CABLE USB] Si usas cable: activa 'Depuración USB' en el celular o 'Anclaje USB'.");
        Console.ResetColor();
    }

    private static void ConfigureFirewall()
    {
        try
        {
            using var id = WindowsIdentity.GetCurrent();
            var principal = new WindowsPrincipal(id);
            if (principal.IsInRole(WindowsBuiltInRole.Administrator))
            {
                var psi = new ProcessStartInfo("netsh", "advfirewall firewall show rule name=\"ATS GPS Server\"")
                {
                    RedirectStandardOutput = true,
                    UseShellExecute = false,
                    CreateNoWindow = true
                };
                using var check = Process.Start(psi);
                check?.WaitForExit(1000);
                if (check?.ExitCode != 0)
                {
                    var addPsi = new ProcessStartInfo("netsh", $"advfirewall firewall add rule name=\"ATS GPS Server\" dir=in action=allow protocol=TCP localport={ServerPort} profile=private")
                    {
                        RedirectStandardOutput = true,
                        UseShellExecute = false,
                        CreateNoWindow = true
                    };
                    using var addProc = Process.Start(addPsi);
                    addProc?.WaitForExit(1500);
                    Console.WriteLine("[FIREWALL] Regla agregada para puerto 35500 TCP.");
                }
            }
        }
        catch { }
    }
}
