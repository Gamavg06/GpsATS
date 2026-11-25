using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;

namespace AtsGpsLauncher;

public partial class MainWindow : Window
{
    private const string PlatformToolsUrl = "https://developer.android.com/tools/releases/platform-tools";
    private const string PreferredApkName = "AtsGpsApp_v1.4.2.apk";
    private static readonly HttpClient StatusHttpClient = new() { Timeout = TimeSpan.FromMilliseconds(1500) };
    private static readonly string[] VirtualAdapterKeywords =
    {
        "WSL", "Hyper-V", "vEthernet", "VirtualBox", "VMware",
        "Tailscale", "ZeroTier", "TAP", "VPN"
    };
    private Process? _serverProcess;
    private string _rootDir = "";
    private string _adbPath = "";
    private string _serverPin = "----";
    private bool _pluginInstalled;
    private volatile bool _isStoppingServer;
    private DispatcherTimer? _statusTimer;

    public MainWindow()
    {
        InitializeComponent();
        Loaded += MainWindow_Loaded;
        Closing += MainWindow_Closing;
    }

    private async void MainWindow_Loaded(object sender, RoutedEventArgs e)
    {
        ResolveDirectories();
        DisplayLocalIps();
        AppendLog("[INFO] Inicializando Panel de Control SGNIA ATS GPS v1.4.2...");

        // 1. Iniciar Servidor en segundo plano (sin ventana negra cmd)
        StartServer();
        StartStatusPolling();

        // 2. Comprobar y configurar USB ADB Reverse
        await CheckAndConfigureAdbAsync();
    }

    private void ResolveDirectories()
    {
        string current = AppDomain.CurrentDomain.BaseDirectory;
        // Buscar carpeta raíz buscando apks o server\publish
        DirectoryInfo? dir = new DirectoryInfo(current);
        while (dir != null)
        {
            if (Directory.Exists(Path.Combine(dir.FullName, "server", "publish")) ||
                Directory.Exists(Path.Combine(dir.FullName, "apks")))
            {
                _rootDir = dir.FullName;
                break;
            }
            dir = dir.Parent;
        }

        if (string.IsNullOrEmpty(_rootDir))
        {
            _rootDir = current.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        }

        _adbPath = ResolveAdbPath();
    }

    private string ResolveAdbPath()
    {
        string appBaseDir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        string[] candidates =
        {
            Path.Combine(appBaseDir, "platform-tools", "adb.exe"),
            Path.Combine(_rootDir, "platform-tools", "adb.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Android\Sdk\platform-tools\adb.exe")
        };

        foreach (string candidate in candidates)
        {
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }

        string? pathEnv = Environment.GetEnvironmentVariable("PATH");
        if (!string.IsNullOrWhiteSpace(pathEnv))
        {
            foreach (string rawDir in pathEnv.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
            {
                string dir = rawDir.Trim().Trim('"');
                if (string.IsNullOrEmpty(dir)) continue;
                try
                {
                    string adbInPath = Path.Combine(dir, "adb.exe");
                    if (File.Exists(adbInPath))
                    {
                        return adbInPath;
                    }
                }
                catch
                {
                    // Ignorar entradas invalidas en PATH
                }
            }
        }

        return "";
    }

    private void SetAdbNotInstalledState()
    {
        var warnBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#F7C948"));
        UsbStatusLed.Fill = warnBrush;
        UsbStatusText.Text = "ADB no instalado (opcional para USB)";
        UsbStatusText.Foreground = warnBrush;
        UsbDeviceText.Text = "Wi-Fi disponible sin ADB; USB requiere Platform-Tools";
        UsbLinkText.Visibility = Visibility.Collapsed;
        BtnDownloadPlatformTools.Visibility = Visibility.Visible;
    }

    private void DisplayLocalIps()
    {
        try
        {
            var candidates = new List<(int Priority, int TypeOrder, string Ip)>();

            foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType != NetworkInterfaceType.Ethernet &&
                    ni.NetworkInterfaceType != NetworkInterfaceType.Wireless80211)
                {
                    continue;
                }

                if (IsVirtualAdapter(ni.Name, ni.Description)) continue;

                foreach (UnicastIPAddressInformation ua in ni.GetIPProperties().UnicastAddresses)
                {
                    IPAddress addr = ua.Address;
                    if (addr.AddressFamily != AddressFamily.InterNetwork || IPAddress.IsLoopback(addr))
                    {
                        continue;
                    }

                    byte[] bytes = addr.GetAddressBytes();
                    if (bytes[0] == 169 && bytes[1] == 254) continue; // Excluir APIPA

                    int priority = GetLanPriority(bytes);
                    int typeOrder = ni.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ? 0 : 1;
                    candidates.Add((priority, typeOrder, addr.ToString()));
                }
            }

            string lanIp = candidates
                .OrderBy(c => c.Priority)
                .ThenBy(c => c.TypeOrder)
                .Select(c => c.Ip)
                .FirstOrDefault() ?? "No detectada";

            ServerIpText.Text = $"IP: 127.0.0.1 (USB) • LAN: {lanIp}";
        }
        catch
        {
            ServerIpText.Text = "IP: 127.0.0.1 (USB Cable)";
        }
    }

    private static bool IsVirtualAdapter(string name, string description)
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

    private static int GetLanPriority(byte[] bytes)
    {
        if (bytes[0] == 192 && bytes[1] == 168) return 0; // RFC 1918: 192.168.0.0/16
        if (bytes[0] == 10) return 1;                      // RFC 1918: 10.0.0.0/8
        if (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31) return 2; // RFC 1918: 172.16.0.0/12
        return 3;
    }

    private void StartServer()
    {
        try
        {
            StopServer();
            _isStoppingServer = false;
            _serverPin = "----";
            PinText.Text = "----";

            string serverExe = Path.Combine(_rootDir, @"server\publish\AtsGpsServer.exe");
            if (!File.Exists(serverExe))
            {
                AppendLog($"[ERROR] No se encontró el ejecutable del servidor en: {serverExe}");
                ServerStatusLed.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#EF4444"));
                ServerStatusText.Text = "ERROR AL INICIAR";
                ServerStatusText.Foreground = ServerStatusLed.Fill;
                return;
            }

            var startInfo = new ProcessStartInfo
            {
                FileName = serverExe,
                WorkingDirectory = Path.Combine(_rootDir, @"server\publish"),
                UseShellExecute = false,
                CreateNoWindow = true, // SIN VENTANA NEGRA DE CMD
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = System.Text.Encoding.UTF8,
                StandardErrorEncoding = System.Text.Encoding.UTF8
            };

            _serverProcess = new Process { StartInfo = startInfo, EnableRaisingEvents = true };
            _serverProcess.Exited += ServerProcess_Exited;
            _serverProcess.OutputDataReceived += (s, ev) =>
            {
                if (!string.IsNullOrEmpty(ev.Data) && !Dispatcher.HasShutdownStarted && !Dispatcher.HasShutdownFinished)
                {
                    _ = Dispatcher.InvokeAsync(() => HandleServerLog(ev.Data));
                }
            };
            _serverProcess.ErrorDataReceived += (s, ev) =>
            {
                if (!string.IsNullOrEmpty(ev.Data) && !Dispatcher.HasShutdownStarted && !Dispatcher.HasShutdownFinished)
                {
                    _ = Dispatcher.InvokeAsync(() => AppendLog($"[STDERR] {ev.Data}"));
                }
            };

            _serverProcess.Start();
            _serverProcess.BeginOutputReadLine();
            _serverProcess.BeginErrorReadLine();

            ServerStatusLed.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#C7F36B"));
            ServerStatusText.Text = "ACTIVO • PUERTO 35500";
            ServerStatusText.Foreground = ServerStatusLed.Fill;
            AppendLog($"[INFO] Servidor AtsGpsServer iniciado con éxito (PID {_serverProcess.Id}).");
        }
        catch (Exception ex)
        {
            AppendLog($"[ERROR] Fallo al iniciar el servidor: {ex.Message}");
            ServerStatusLed.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF5252"));
            ServerStatusText.Text = "DETENIDO";
            ServerStatusText.Foreground = ServerStatusLed.Fill;
        }
    }

    private void ServerProcess_Exited(object? sender, EventArgs e)
    {
        if (_isStoppingServer || Dispatcher.HasShutdownStarted || Dispatcher.HasShutdownFinished)
        {
            return;
        }

        var exitedProc = sender as Process;
        int? exitCode = null;
        if (exitedProc != null)
        {
            try { exitCode = exitedProc.ExitCode; } catch { }
        }

        _ = Dispatcher.InvokeAsync(() =>
        {
            if (_isStoppingServer || !ReferenceEquals(exitedProc, _serverProcess))
            {
                return;
            }

            var errorBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#EF4444"));
            ServerStatusLed.Fill = errorBrush;
            ServerStatusText.Text = "SERVIDOR DETENIDO (ERROR)";
            ServerStatusText.Foreground = errorBrush;

            SetAtsDisconnectedState("DESCONECTADO");

            string codeText = exitCode.HasValue ? exitCode.Value.ToString() : "desconocido";
            AppendLog($"[ERROR] AtsGpsServer.exe termino inesperadamente (codigo de salida: {codeText}).");
        });
    }

    private void StopServer()
    {
        _isStoppingServer = true;
        try
        {
            if (_serverProcess != null)
            {
                var proc = _serverProcess;
                _serverProcess = null;
                if (!proc.HasExited)
                {
                    proc.Kill(true);
                    proc.WaitForExit(3000);
                }
                proc.Dispose();
            }

            // Asegurar que no queden procesos huérfanos de AtsGpsServer
            foreach (var p in Process.GetProcessesByName("AtsGpsServer"))
            {
                using (p)
                {
                    try
                    {
                        if (!p.HasExited)
                        {
                            p.Kill(true);
                            p.WaitForExit(3000);
                        }
                    }
                    catch { }
                }
            }
        }
        catch { }
    }

    private void HandleServerLog(string line)
    {
        AppendLog(line);

        // Detectar PIN en el banner de seguridad del servidor
        if (line.Contains("PIN", StringComparison.OrdinalIgnoreCase))
        {
            var match = Regex.Match(line, @"\bPIN\s*:\s*(\d{4,6})\b", RegexOptions.IgnoreCase);
            if (match.Success)
            {
                _serverPin = match.Groups[1].Value;
                PinText.Text = _serverPin;
            }
        }

        if (line.Contains("[OK] Plugin scs-telemetry.dll", StringComparison.OrdinalIgnoreCase))
        {
            _pluginInstalled = true;
            AtsStatusLed.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#38BDF8"));
            AtsStatusText.Text = "PLUGIN SDK LISTO";
            AtsStatusText.Foreground = AtsStatusLed.Fill;
        }
        else if (line.Contains("No se detecto scs-telemetry.dll", StringComparison.OrdinalIgnoreCase) ||
                 line.Contains("No se detectó scs-telemetry.dll", StringComparison.OrdinalIgnoreCase))
        {
            _pluginInstalled = false;
            AtsStatusLed.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#F7C948"));
            AtsStatusText.Text = "PLUGIN NO DETECTADO";
            AtsStatusText.Foreground = AtsStatusLed.Fill;
        }
    }

    private void StartStatusPolling()
    {
        _statusTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3) };
        _statusTimer.Tick += async (_, _) => await RefreshAtsStatusAsync();
        _statusTimer.Start();
    }

    private void SetAtsDisconnectedState(string statusText)
    {
        var disconnectedBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#94A3B8"));
        AtsStatusLed.Fill = disconnectedBrush;
        AtsStatusText.Text = statusText;
        AtsStatusText.Foreground = disconnectedBrush;
    }

    private async Task RefreshAtsStatusAsync()
    {
        if (Dispatcher.HasShutdownStarted || Dispatcher.HasShutdownFinished) return;

        if (_serverProcess == null || _serverProcess.HasExited)
        {
            SetAtsDisconnectedState("DESCONECTADO");
            return;
        }

        try
        {
            using var response = await StatusHttpClient.GetAsync("http://127.0.0.1:35500/api/status");
            if (!response.IsSuccessStatusCode)
            {
                SetAtsDisconnectedState("SERVIDOR NO RESPONDE");
                return;
            }

            string json = await response.Content.ReadAsStringAsync();
            using var doc = JsonDocument.Parse(json);
            if (doc.RootElement.TryGetProperty("atsConnected", out var connectedProp))
            {
                bool atsConnected = connectedProp.GetBoolean();
                if (atsConnected)
                {
                    AtsStatusLed.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#C7F36B"));
                    AtsStatusText.Text = "SDK SCS ACTIVO";
                    AtsStatusText.Foreground = AtsStatusLed.Fill;
                }
                else if (_pluginInstalled)
                {
                    AtsStatusLed.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#38BDF8"));
                    AtsStatusText.Text = "ESPERANDO JUEGO ATS";
                    AtsStatusText.Foreground = AtsStatusLed.Fill;
                }
                else
                {
                    AtsStatusLed.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#F7C948"));
                    AtsStatusText.Text = "PLUGIN NO DETECTADO";
                    AtsStatusText.Foreground = AtsStatusLed.Fill;
                }
            }
        }
        catch
        {
            if (!Dispatcher.HasShutdownStarted && !Dispatcher.HasShutdownFinished)
            {
                SetAtsDisconnectedState("SERVIDOR NO RESPONDE");
            }
        }
    }

    private async Task CheckAndConfigureAdbAsync()
    {
        _adbPath = ResolveAdbPath();
        if (string.IsNullOrEmpty(_adbPath))
        {
            SetAdbNotInstalledState();
            AppendLog($"[USB] No se encontro adb.exe. La conexion por Wi-Fi funciona sin ADB; si deseas usar cable USB, puedes descargar Google Android SDK Platform-Tools (Apache 2.0) en {PlatformToolsUrl} y extraerlo en 'platform-tools\\' junto al lanzador o en el SDK de Android.");
            return;
        }

        UsbLinkText.Visibility = Visibility.Visible;
        BtnDownloadPlatformTools.Visibility = Visibility.Collapsed;
        AppendLog($"[USB] Verificando conexion de cable USB (ADB: {_adbPath})...");
        try
        {
            var (_, devicesOutput) = await RunCommandAsync(_adbPath, "devices -l");
            if (!string.IsNullOrWhiteSpace(devicesOutput))
            {
                AppendLog(devicesOutput.Trim());
            }

            bool hasDevice = devicesOutput.Split('\n')
                .Select(l => l.Trim())
                .Any(l => Regex.IsMatch(l, @"^\S+\s+device\b"));

            if (hasDevice)
            {
                var (revExitCode, revOutput) = await RunCommandAsync(_adbPath, "reverse tcp:35500 tcp:35500");
                if (!string.IsNullOrWhiteSpace(revOutput))
                {
                    AppendLog($"[USB] adb reverse: {revOutput.Trim()}");
                }

                if (revExitCode == 0)
                {
                    UsbStatusLed.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#C7F36B"));
                    UsbStatusText.Text = "CONECTADO • REVERSE ACTIVO";
                    UsbStatusText.Foreground = UsbStatusLed.Fill;
                    UsbDeviceText.Text = "Celular detectado y enlazado via USB";
                    AppendLog("[USB] Puerto 35500 enlazado correctamente al celular (adb reverse). Ingresa el PIN de 6 digitos en la app Android.");
                }
                else
                {
                    var errorBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF5252"));
                    UsbStatusLed.Fill = errorBrush;
                    UsbStatusText.Text = "ERROR ADB REVERSE";
                    UsbStatusText.Foreground = errorBrush;
                    UsbDeviceText.Text = "No se pudo enlazar puerto 35500 (revisa logs)";
                    AppendLog($"[USB] Fallo 'adb reverse tcp:35500 tcp:35500' (codigo {revExitCode}).");
                }
            }
            else
            {
                UsbStatusLed.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#F7C948"));
                UsbStatusText.Text = "ESPERANDO CABLE USB";
                UsbStatusText.Foreground = UsbStatusLed.Fill;
                UsbDeviceText.Text = "Conecta el celular con Depuracion USB";
                AppendLog("[USB] No se detecto celular por cable. Si usas Wi-Fi puedes conectarte directo por la IP local.");
            }
        }
        catch (Exception ex)
        {
            AppendLog($"[USB] Error al verificar ADB: {ex.Message}");
            UsbStatusLed.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF5252"));
            UsbStatusText.Text = "ERROR ADB";
            UsbStatusText.Foreground = UsbStatusLed.Fill;
        }
    }

    private void BtnDownloadPlatformTools_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Process.Start(new ProcessStartInfo(PlatformToolsUrl) { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            AppendLog($"[ERROR] No se pudo abrir el enlace de Platform-Tools: {ex.Message}");
        }
    }

    private async void BtnReverseUsb_Click(object sender, RoutedEventArgs e)
    {
        AppendLog("[ACCION] Re-enlazando puerto USB ADB...");
        await CheckAndConfigureAdbAsync();
    }

    private string? ResolveApkPath()
    {
        string apksDir = Path.Combine(_rootDir, "apks");
        string preferred = Path.Combine(apksDir, PreferredApkName);
        if (File.Exists(preferred))
        {
            return preferred;
        }

        if (Directory.Exists(apksDir))
        {
            return Directory.GetFiles(apksDir, "*.apk")
                .OrderBy(f => f, StringComparer.OrdinalIgnoreCase)
                .FirstOrDefault();
        }

        return null;
    }

    private async void BtnInstallApk_Click(object sender, RoutedEventArgs e)
    {
        string? apkPath = ResolveApkPath();
        if (string.IsNullOrEmpty(apkPath) || !File.Exists(apkPath))
        {
            string expectedPath = Path.Combine(_rootDir, "apks", PreferredApkName);
            AppendLog($"[ERROR] No se encontro el APK en: {expectedPath}");
            MessageBox.Show($"No se encontro apks\\{PreferredApkName} ni ningun APK en la carpeta apks.", "Error", MessageBoxButton.OK, MessageBoxImage.Error);
            return;
        }

        _adbPath = ResolveAdbPath();
        if (string.IsNullOrEmpty(_adbPath))
        {
            SetAdbNotInstalledState();
            AppendLog($"[USB] No se encontro adb.exe para instalar {Path.GetFileName(apkPath)}. Descarga Google Android SDK Platform-Tools (Apache 2.0) en {PlatformToolsUrl} o copia el APK manualmente al telefono.");
            MessageBox.Show(
                $"ADB no esta instalado (opcional para USB).\n\nPara instalar por cable USB, descarga Android SDK Platform-Tools en:\n{PlatformToolsUrl}\ny extrae 'platform-tools\\' junto al lanzador.\n\nTambien puedes copiar {Path.GetFileName(apkPath)} manualmente a tu dispositivo.",
                "ADB no instalado",
                MessageBoxButton.OK,
                MessageBoxImage.Information);
            return;
        }

        BtnInstallApk.IsEnabled = false;
        AppendLog($"[INSTALL] Instalando {Path.GetFileName(apkPath)} en el celular via USB...");
        TxtFooterStatus.Text = "Instalando APK en celular...";
        TxtFooterStatus.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#F7C948"));

        try
        {
            var (installExitCode, result) = await RunCommandAsync(_adbPath, $"install -r \"{apkPath}\"", 60000);
            if (!string.IsNullOrWhiteSpace(result))
            {
                AppendLog(result.Trim());
            }
            if (installExitCode == 0 && result.Contains("Success"))
            {
                TxtFooterStatus.Text = "APK instalada con exito en el celular.";
                TxtFooterStatus.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#C7F36B"));
                MessageBox.Show("La aplicacion se instalo y actualizo correctamente en tu celular.", "Instalacion Exitosa", MessageBoxButton.OK, MessageBoxImage.Information);
            }
            else
            {
                TxtFooterStatus.Text = "Error al instalar APK (revisa logs).";
                TxtFooterStatus.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF5252"));
            }
        }
        catch (Exception ex)
        {
            AppendLog($"[ERROR] Fallo al instalar APK: {ex.Message}");
        }
        finally
        {
            BtnInstallApk.IsEnabled = true;
        }
    }

    private void BtnRestartServer_Click(object sender, RoutedEventArgs e)
    {
        AppendLog("[ACCION] Reiniciando servidor ATS GPS...");
        StartServer();
    }

    private void BtnOpenApksFolder_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            string apksDir = Path.Combine(_rootDir, "apks");
            if (Directory.Exists(apksDir))
            {
                Process.Start(new ProcessStartInfo("explorer.exe", $"\"{apksDir}\"") { UseShellExecute = true });
            }
        }
        catch (Exception ex)
        {
            AppendLog($"[ERROR] No se pudo abrir la carpeta: {ex.Message}");
        }
    }

    private void BtnClearLogs_Click(object sender, RoutedEventArgs e)
    {
        TxtConsoleLog.Clear();
    }

    private void BtnCopyPin_Click(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrEmpty(_serverPin) && _serverPin != "----")
        {
            try
            {
                Clipboard.SetText(_serverPin);
                MessageBox.Show($"PIN {_serverPin} copiado al portapapeles.", "Copiado", MessageBoxButton.OK, MessageBoxImage.Information);
            }
            catch (Exception ex)
            {
                AppendLog($"[AVISO] No se pudo copiar el PIN al portapapeles: {ex.Message}");
                MessageBox.Show($"No se pudo acceder al portapapeles ({ex.Message}). PIN actual: {_serverPin}", "Portapapeles ocupado", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        }
    }

    private async Task<(int ExitCode, string Output)> RunCommandAsync(string fileName, string args, int timeoutMs = 15000)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = fileName,
                Arguments = args,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = System.Text.Encoding.UTF8,
                StandardErrorEncoding = System.Text.Encoding.UTF8,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            using var p = Process.Start(psi);
            if (p == null) return (-1, "No se pudo iniciar el proceso.");

            var stdoutTask = p.StandardOutput.ReadToEndAsync();
            var stderrTask = p.StandardError.ReadToEndAsync();

            using var cts = new CancellationTokenSource(timeoutMs);
            try
            {
                await p.WaitForExitAsync(cts.Token);
            }
            catch (OperationCanceledException)
            {
                try { p.Kill(true); } catch { }
                return (-1, "Tiempo de espera agotado ejecutando el comando.");
            }

            string output = await stdoutTask;
            string error = await stderrTask;
            string combined = output + (string.IsNullOrEmpty(error) ? "" : "\n" + error);
            return (p.ExitCode, combined);
        }
        catch (Exception ex)
        {
            return (-1, "Error: " + ex.Message);
        }
    }

    private void AppendLog(string message)
    {
        string timestamp = DateTime.Now.ToString("HH:mm:ss");
        TxtConsoleLog.AppendText($"[{timestamp}] {message}\n");
        LogScrollViewer.ScrollToEnd();
    }

    private void MainWindow_Closing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        _statusTimer?.Stop();
        StopServer();
    }
}
