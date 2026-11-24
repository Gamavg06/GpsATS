using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
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
    private static readonly HttpClient StatusHttpClient = new() { Timeout = TimeSpan.FromMilliseconds(1500) };
    private Process? _serverProcess;
    private string _rootDir = "";
    private string _adbPath = "";
    private string _serverPin = "----";
    private bool _pluginInstalled;
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

        // Resolver ADB
        string defaultSdkAdb = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            @"Android\Sdk\platform-tools\adb.exe"
        );

        if (File.Exists(defaultSdkAdb))
        {
            _adbPath = defaultSdkAdb;
        }
        else
        {
            _adbPath = "adb";
        }
    }

    private void DisplayLocalIps()
    {
        try
        {
            var host = Dns.GetHostEntry(Dns.GetHostName());
            var ip = host.AddressList.FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a));
            string lanIp = ip?.ToString() ?? "No detectada";
            ServerIpText.Text = $"IP: 127.0.0.1 (USB) • LAN: {lanIp}";
        }
        catch
        {
            ServerIpText.Text = "IP: 127.0.0.1 (USB Cable)";
        }
    }

    private void StartServer()
    {
        try
        {
            StopServer();

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

    private void StopServer()
    {
        try
        {
            if (_serverProcess != null && !_serverProcess.HasExited)
            {
                _serverProcess.Kill(true);
                _serverProcess.Dispose();
                _serverProcess = null;
            }

            // Asegurar que no queden procesos huérfanos de AtsGpsServer
            foreach (var p in Process.GetProcessesByName("AtsGpsServer"))
            {
                using (p)
                {
                    try { p.Kill(); } catch { }
                }
            }
        }
        catch { }
    }

    private void HandleServerLog(string line)
    {
        AppendLog(line);

        // Detectar PIN en el log si el servidor lo imprime
        if (line.Contains("PIN", StringComparison.OrdinalIgnoreCase))
        {
            var match = Regex.Match(line, @"\b\d{4,6}\b");
            if (match.Success)
            {
                _serverPin = match.Value;
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
        else if (line.Contains("No se detectó scs-telemetry.dll", StringComparison.OrdinalIgnoreCase))
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

    private async Task RefreshAtsStatusAsync()
    {
        if (Dispatcher.HasShutdownStarted || Dispatcher.HasShutdownFinished) return;
        try
        {
            using var response = await StatusHttpClient.GetAsync("http://127.0.0.1:35500/api/status");
            if (!response.IsSuccessStatusCode) return;

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
            }
        }
        catch
        {
            // Ignorar si el servidor aún está iniciando o fue detenido
        }
    }

    private async Task CheckAndConfigureAdbAsync()
    {
        AppendLog("[USB] Verificando conexión de cable USB (ADB)...");
        try
        {
            string devicesOutput = await RunCommandAsync(_adbPath, "devices -l");
            AppendLog(devicesOutput.Trim());

            bool hasDevice = devicesOutput.Split('\n')
                .Select(l => l.Trim())
                .Any(l => Regex.IsMatch(l, @"^\S+\s+device\b"));

            if (hasDevice)
            {
                string revOutput = await RunCommandAsync(_adbPath, "reverse tcp:35500 tcp:35500");
                UsbStatusLed.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#C7F36B"));
                UsbStatusText.Text = "CONECTADO • REVERSE ACTIVO";
                UsbStatusText.Foreground = UsbStatusLed.Fill;
                UsbDeviceText.Text = "Celular detectado y enlazado vía USB";
                AppendLog("[USB] Puerto 35500 enlazado correctamente al celular (adb reverse).");
            }
            else
            {
                UsbStatusLed.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#F7C948"));
                UsbStatusText.Text = "ESPERANDO CABLE USB";
                UsbStatusText.Foreground = UsbStatusLed.Fill;
                UsbDeviceText.Text = "Conecta el celular con Depuración USB";
                AppendLog("[USB] No se detectó celular por cable. Si usas Wi-Fi puedes conectarte directo por la IP local.");
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

    private async void BtnReverseUsb_Click(object sender, RoutedEventArgs e)
    {
        AppendLog("[ACCION] Re-enlazando puerto USB ADB...");
        await CheckAndConfigureAdbAsync();
    }

    private async void BtnInstallApk_Click(object sender, RoutedEventArgs e)
    {
        string apkPath = Path.Combine(_rootDir, @"apks\release.apk");
        if (!File.Exists(apkPath))
        {
            AppendLog($"[ERROR] No se encontró el APK en: {apkPath}");
            MessageBox.Show("No se encontró apks/release.apk", "Error", MessageBoxButton.OK, MessageBoxImage.Error);
            return;
        }

        AppendLog($"[INSTALL] Instalando {Path.GetFileName(apkPath)} en el celular vía USB...");
        TxtFooterStatus.Text = "Instalando APK en celular...";
        TxtFooterStatus.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#F7C948"));

        try
        {
            string result = await RunCommandAsync(_adbPath, $"install -r \"{apkPath}\"");
            AppendLog(result.Trim());
            if (result.Contains("Success"))
            {
                TxtFooterStatus.Text = "¡APK instalada con éxito en el celular!";
                TxtFooterStatus.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#C7F36B"));
                MessageBox.Show("¡La aplicación se instaló y actualizó correctamente en tu celular!", "Instalación Exitosa", MessageBoxButton.OK, MessageBoxImage.Information);
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
                Process.Start("explorer.exe", apksDir);
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
            Clipboard.SetText(_serverPin);
            MessageBox.Show($"PIN {_serverPin} copiado al portapapeles.", "Copiado", MessageBoxButton.OK, MessageBoxImage.Information);
        }
    }

    private async Task<string> RunCommandAsync(string fileName, string args)
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
            if (p == null) return "No se pudo iniciar el proceso.";

            var stdoutTask = p.StandardOutput.ReadToEndAsync();
            var stderrTask = p.StandardError.ReadToEndAsync();

            using var cts = new CancellationTokenSource(10000);
            try
            {
                await p.WaitForExitAsync(cts.Token);
            }
            catch (OperationCanceledException)
            {
                try { p.Kill(true); } catch { }
                return "Tiempo de espera agotado ejecutando el comando.";
            }

            string output = await stdoutTask;
            string error = await stderrTask;
            return output + (string.IsNullOrEmpty(error) ? "" : "\n" + error);
        }
        catch (Exception ex)
        {
            return "Error: " + ex.Message;
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
