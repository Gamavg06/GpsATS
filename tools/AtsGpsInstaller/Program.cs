using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text.RegularExpressions;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("Instalador SGNIA - ATS Telemetria GPS")]
[assembly: AssemblyProduct("ATS Telemetria GPS")]
[assembly: AssemblyCompany("SGNIA")]
[assembly: AssemblyCopyright("Copyright (c) 2026 Gamaliel Velazquez Galicia (SGNIA)")]
[assembly: AssemblyDescription("Instalador oficial autogestionado para SGNIA - ATS Telemetry & GPS")]
[assembly: AssemblyVersion("1.4.2.0")]
[assembly: AssemblyFileVersion("1.4.2.0")]

namespace AtsGpsInstaller
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new InstallerForm());
        }
    }

    public class InstallerForm : Form
    {
        private TextBox txtInstallDir;
        private TextBox txtAtsDir;
        private CheckBox chkInstallPlugin;
        private CheckBox chkDesktopShortcut;
        private CheckBox chkStartMenu;
        private CheckBox chkFirewall;
        private CheckBox chkLaunchAfter;
        private Button btnInstall;
        private ProgressBar progressBar;
        private Label lblStatus;
        private Panel panelSetup;
        private Panel panelProgress;
        private Panel panelDone;
        private BackgroundWorker worker;

        private Color bgDark = Color.FromArgb(12, 16, 19);
        private Color panelDark = Color.FromArgb(21, 28, 33);
        private Color cyanAccent = Color.FromArgb(56, 189, 248);
        private Color limeAccent = Color.FromArgb(199, 243, 107);
        private Color textWhite = Color.FromArgb(241, 245, 249);
        private Color textGray = Color.FromArgb(148, 163, 184);

        public InstallerForm()
        {
            this.Text = "Instalador SGNIA - ATS Telemetria GPS v1.4.2";
            this.Size = new Size(680, 560);
            this.StartPosition = FormStartPosition.CenterScreen;
            this.FormBorderStyle = FormBorderStyle.FixedDialog;
            this.MaximizeBox = false;
            this.BackColor = bgDark;
            this.ForeColor = textWhite;

            try
            {
                using (Stream iconStream = Assembly.GetExecutingAssembly().GetManifestResourceStream("app.ico"))
                {
                    if (iconStream != null)
                    {
                        this.Icon = new Icon(iconStream);
                    }
                }
            }
            catch { }

            BuildUI();
            DetectAtsPath();
        }

        private void BuildUI()
        {
            // Panel de Encabezado
            Panel header = new Panel();
            header.Dock = DockStyle.Top;
            header.Height = 85;
            header.BackColor = panelDark;
            this.Controls.Add(header);

            Label title = new Label();
            title.Text = "ATS TELEMETRIA GPS - INSTALADOR PC";
            title.Font = new Font("Segoe UI", 14F, FontStyle.Bold);
            title.ForeColor = cyanAccent;
            title.Location = new Point(24, 18);
            title.AutoSize = true;
            header.Controls.Add(title);

            Label subtitle = new Label();
            subtitle.Text = "Asistente de configuracion y sincronizacion companion v1.4.2";
            subtitle.Font = new Font("Segoe UI", 9.5F, FontStyle.Regular);
            subtitle.ForeColor = textGray;
            subtitle.Location = new Point(25, 48);
            subtitle.AutoSize = true;
            header.Controls.Add(subtitle);

            // Panel Setup Principal
            panelSetup = new Panel();
            panelSetup.Location = new Point(24, 100);
            panelSetup.Size = new Size(620, 390);
            this.Controls.Add(panelSetup);

            int y = 5;
            Label lblDir = new Label();
            lblDir.Text = "Carpeta de instalacion en tu PC:";
            lblDir.Font = new Font("Segoe UI", 9F, FontStyle.Bold);
            lblDir.ForeColor = textWhite;
            lblDir.Location = new Point(0, y);
            lblDir.AutoSize = true;
            panelSetup.Controls.Add(lblDir);

            y += 24;
            txtInstallDir = new TextBox();
            txtInstallDir.Text = @"C:\Telemetriagps";
            txtInstallDir.Location = new Point(0, y);
            txtInstallDir.Size = new Size(510, 26);
            txtInstallDir.BackColor = panelDark;
            txtInstallDir.ForeColor = textWhite;
            txtInstallDir.Font = new Font("Segoe UI", 9.5F);
            panelSetup.Controls.Add(txtInstallDir);

            Button btnBrowseInstall = new Button();
            btnBrowseInstall.Text = "Examinar...";
            btnBrowseInstall.Location = new Point(520, y - 2);
            btnBrowseInstall.Size = new Size(100, 28);
            btnBrowseInstall.BackColor = panelDark;
            btnBrowseInstall.ForeColor = cyanAccent;
            btnBrowseInstall.FlatStyle = FlatStyle.Flat;
            btnBrowseInstall.Click += delegate
            {
                using (FolderBrowserDialog fbd = new FolderBrowserDialog())
                {
                    fbd.SelectedPath = txtInstallDir.Text;
                    if (fbd.ShowDialog() == DialogResult.OK)
                    {
                        txtInstallDir.Text = fbd.SelectedPath;
                    }
                }
            };
            panelSetup.Controls.Add(btnBrowseInstall);

            y += 45;
            Label lblAts = new Label();
            lblAts.Text = "Carpeta de American Truck Simulator (Steam):";
            lblAts.Font = new Font("Segoe UI", 9F, FontStyle.Bold);
            lblAts.ForeColor = textWhite;
            lblAts.Location = new Point(0, y);
            lblAts.AutoSize = true;
            panelSetup.Controls.Add(lblAts);

            y += 24;
            txtAtsDir = new TextBox();
            txtAtsDir.Text = @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
            txtAtsDir.Location = new Point(0, y);
            txtAtsDir.Size = new Size(510, 26);
            txtAtsDir.BackColor = panelDark;
            txtAtsDir.ForeColor = textWhite;
            txtAtsDir.Font = new Font("Segoe UI", 9.5F);
            panelSetup.Controls.Add(txtAtsDir);

            Button btnBrowseAts = new Button();
            btnBrowseAts.Text = "Examinar...";
            btnBrowseAts.Location = new Point(520, y - 2);
            btnBrowseAts.Size = new Size(100, 28);
            btnBrowseAts.BackColor = panelDark;
            btnBrowseAts.ForeColor = cyanAccent;
            btnBrowseAts.FlatStyle = FlatStyle.Flat;
            btnBrowseAts.Click += delegate
            {
                using (FolderBrowserDialog fbd = new FolderBrowserDialog())
                {
                    fbd.SelectedPath = txtAtsDir.Text;
                    if (fbd.ShowDialog() == DialogResult.OK)
                    {
                        txtAtsDir.Text = fbd.SelectedPath;
                    }
                }
            };
            panelSetup.Controls.Add(btnBrowseAts);

            y += 45;
            Label lblOptions = new Label();
            lblOptions.Text = "Opciones de configuracion automatica:";
            lblOptions.Font = new Font("Segoe UI", 9F, FontStyle.Bold);
            lblOptions.ForeColor = textWhite;
            lblOptions.Location = new Point(0, y);
            lblOptions.AutoSize = true;
            panelSetup.Controls.Add(lblOptions);

            y += 26;
            chkInstallPlugin = new CheckBox();
            chkInstallPlugin.Text = "Instalar plugin scs-telemetry.dll en la carpeta del juego";
            chkInstallPlugin.Checked = true;
            chkInstallPlugin.ForeColor = textWhite;
            chkInstallPlugin.Font = new Font("Segoe UI", 9F);
            chkInstallPlugin.Location = new Point(5, y);
            chkInstallPlugin.AutoSize = true;
            panelSetup.Controls.Add(chkInstallPlugin);

            y += 26;
            chkDesktopShortcut = new CheckBox();
            chkDesktopShortcut.Text = "Crear acceso directo en el Escritorio con icono del logo";
            chkDesktopShortcut.Checked = true;
            chkDesktopShortcut.ForeColor = textWhite;
            chkDesktopShortcut.Font = new Font("Segoe UI", 9F);
            chkDesktopShortcut.Location = new Point(5, y);
            chkDesktopShortcut.AutoSize = true;
            panelSetup.Controls.Add(chkDesktopShortcut);

            y += 26;
            chkStartMenu = new CheckBox();
            chkStartMenu.Text = "Crear acceso directo en el Menu Inicio";
            chkStartMenu.Checked = true;
            chkStartMenu.ForeColor = textWhite;
            chkStartMenu.Font = new Font("Segoe UI", 9F);
            chkStartMenu.Location = new Point(5, y);
            chkStartMenu.AutoSize = true;
            panelSetup.Controls.Add(chkStartMenu);

            y += 26;
            chkFirewall = new CheckBox();
            chkFirewall.Text = "Crear regla de Firewall de Windows (TCP 35500)";
            chkFirewall.Checked = true;
            chkFirewall.ForeColor = textWhite;
            chkFirewall.Font = new Font("Segoe UI", 9F);
            chkFirewall.Location = new Point(5, y);
            chkFirewall.AutoSize = true;
            panelSetup.Controls.Add(chkFirewall);

            y += 45;
            btnInstall = new Button();
            btnInstall.Text = "INSTALAR AHORA";
            btnInstall.Size = new Size(200, 42);
            btnInstall.Location = new Point(210, y);
            btnInstall.BackColor = cyanAccent;
            btnInstall.ForeColor = Color.FromArgb(10, 20, 28);
            btnInstall.Font = new Font("Segoe UI", 11F, FontStyle.Bold);
            btnInstall.FlatStyle = FlatStyle.Flat;
            btnInstall.Cursor = Cursors.Hand;
            btnInstall.Click += BtnInstall_Click;
            panelSetup.Controls.Add(btnInstall);

            // Panel Progreso
            panelProgress = new Panel();
            panelProgress.Location = new Point(24, 100);
            panelProgress.Size = new Size(620, 390);
            panelProgress.Visible = false;
            this.Controls.Add(panelProgress);

            Label lblProgTitle = new Label();
            lblProgTitle.Text = "Instalando ATS Telemetria GPS...";
            lblProgTitle.Font = new Font("Segoe UI", 12F, FontStyle.Bold);
            lblProgTitle.ForeColor = cyanAccent;
            lblProgTitle.Location = new Point(0, 50);
            lblProgTitle.AutoSize = true;
            panelProgress.Controls.Add(lblProgTitle);

            lblStatus = new Label();
            lblStatus.Text = "Preparando archivos...";
            lblStatus.Font = new Font("Segoe UI", 9.5F);
            lblStatus.ForeColor = textGray;
            lblStatus.Location = new Point(0, 95);
            lblStatus.Size = new Size(610, 25);
            panelProgress.Controls.Add(lblStatus);

            progressBar = new ProgressBar();
            progressBar.Location = new Point(0, 130);
            progressBar.Size = new Size(610, 28);
            progressBar.Style = ProgressBarStyle.Continuous;
            panelProgress.Controls.Add(progressBar);

            // Panel Completado
            panelDone = new Panel();
            panelDone.Location = new Point(24, 100);
            panelDone.Size = new Size(620, 390);
            panelDone.Visible = false;
            this.Controls.Add(panelDone);

            Label lblDoneTitle = new Label();
            lblDoneTitle.Text = "Instalacion completada con exito";
            lblDoneTitle.Font = new Font("Segoe UI", 15F, FontStyle.Bold);
            lblDoneTitle.ForeColor = limeAccent;
            lblDoneTitle.Location = new Point(0, 40);
            lblDoneTitle.AutoSize = true;
            panelDone.Controls.Add(lblDoneTitle);

            Label lblDoneDesc = new Label();
            lblDoneDesc.Text = "Todos los componentes del servidor y la telemetria estan listos.\n\nPuedes conectar tu celular por cable USB o Wi-Fi e instalar la aplicacion movil desde el panel de control.";
            lblDoneDesc.Font = new Font("Segoe UI", 10F);
            lblDoneDesc.ForeColor = textWhite;
            lblDoneDesc.Location = new Point(0, 85);
            lblDoneDesc.Size = new Size(610, 70);
            panelDone.Controls.Add(lblDoneDesc);

            chkLaunchAfter = new CheckBox();
            chkLaunchAfter.Text = "Abrir ATS Telemetria GPS Server ahora";
            chkLaunchAfter.Checked = true;
            chkLaunchAfter.ForeColor = cyanAccent;
            chkLaunchAfter.Font = new Font("Segoe UI", 10F, FontStyle.Bold);
            chkLaunchAfter.Location = new Point(5, 175);
            chkLaunchAfter.AutoSize = true;
            panelDone.Controls.Add(chkLaunchAfter);

            Button btnFinish = new Button();
            btnFinish.Text = "FINALIZAR";
            btnFinish.Size = new Size(180, 40);
            btnFinish.Location = new Point(210, 230);
            btnFinish.BackColor = limeAccent;
            btnFinish.ForeColor = Color.FromArgb(10, 20, 28);
            btnFinish.Font = new Font("Segoe UI", 11F, FontStyle.Bold);
            btnFinish.FlatStyle = FlatStyle.Flat;
            btnFinish.Cursor = Cursors.Hand;
            btnFinish.Click += delegate
            {
                if (chkLaunchAfter.Checked)
                {
                    string exePath = Path.Combine(txtInstallDir.Text, "Iniciar_Servidor.exe");
                    if (File.Exists(exePath))
                    {
                        Process.Start(new ProcessStartInfo(exePath)
                        {
                            WorkingDirectory = txtInstallDir.Text
                        });
                    }
                }
                Application.Exit();
            };
            panelDone.Controls.Add(btnFinish);
        }

        private void DetectAtsPath()
        {
            try
            {
                List<string> candidates = new List<string>();
                candidates.Add(@"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator");
                candidates.Add(@"C:\Program Files\Steam\steamapps\common\American Truck Simulator");
                candidates.Add(@"D:\SteamLibrary\steamapps\common\American Truck Simulator");
                candidates.Add(@"E:\SteamLibrary\steamapps\common\American Truck Simulator");
                candidates.Add(@"F:\SteamLibrary\steamapps\common\American Truck Simulator");

                // Check Registry for Steam
                string steamPath = null;
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(@"Software\Valve\Steam"))
                {
                    if (key != null) steamPath = key.GetValue("SteamPath") as string;
                }
                if (string.IsNullOrEmpty(steamPath))
                {
                    using (RegistryKey key = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\WOW6432Node\Valve\Steam"))
                    {
                        if (key != null) steamPath = key.GetValue("InstallPath") as string;
                    }
                }

                if (!string.IsNullOrEmpty(steamPath))
                {
                    steamPath = steamPath.Replace('/', '\\');
                    candidates.Insert(0, Path.Combine(steamPath, @"steamapps\common\American Truck Simulator"));

                    // Parse libraryfolders.vdf
                    string vdf = Path.Combine(steamPath, @"steamapps\libraryfolders.vdf");
                    if (File.Exists(vdf))
                    {
                        string content = File.ReadAllText(vdf);
                        MatchCollection matches = Regex.Matches(content, "\"path\"\\s+\"([^\"]+)\"");
                        foreach (Match m in matches)
                        {
                            string p = m.Groups[1].Value.Replace("\\\\", "\\").Replace('/', '\\');
                            candidates.Add(Path.Combine(p, @"steamapps\common\American Truck Simulator"));
                        }
                    }
                }

                foreach (string candidate in candidates)
                {
                    if (Directory.Exists(candidate))
                    {
                        txtAtsDir.Text = candidate;
                        break;
                    }
                }
            }
            catch { }
        }

        private void BtnInstall_Click(object sender, EventArgs e)
        {
            panelSetup.Visible = false;
            panelProgress.Visible = true;

            string targetDir = txtInstallDir.Text.Trim();
            string atsDir = txtAtsDir.Text.Trim();
            bool installPlugin = chkInstallPlugin.Checked;
            bool createDesktop = chkDesktopShortcut.Checked;
            bool createStartMenu = chkStartMenu.Checked;
            bool configureFirewall = chkFirewall.Checked;

            worker = new BackgroundWorker();
            worker.WorkerReportsProgress = true;
            worker.DoWork += delegate(object s, DoWorkEventArgs args)
            {
                List<string> warnings = new List<string>();

                // 1. Extraer payload.zip
                worker.ReportProgress(10, "Extrayendo archivos de telemetria...");
                if (!Directory.Exists(targetDir))
                {
                    Directory.CreateDirectory(targetDir);
                }

                string fullTargetDir = Path.GetFullPath(targetDir);
                if (!fullTargetDir.EndsWith(Path.DirectorySeparatorChar.ToString()))
                {
                    fullTargetDir += Path.DirectorySeparatorChar;
                }

                using (Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("payload.zip"))
                {
                    if (stream == null)
                    {
                        throw new Exception("No se encontro el recurso interno payload.zip.");
                    }

                    using (ZipArchive archive = new ZipArchive(stream, ZipArchiveMode.Read))
                    {
                        int total = Math.Max(1, archive.Entries.Count);
                        int count = 0;
                        foreach (ZipArchiveEntry entry in archive.Entries)
                        {
                            count++;
                            int percent = 10 + (count * 60 / total);
                            string destPath = Path.GetFullPath(Path.Combine(fullTargetDir, entry.FullName.Replace('/', '\\')));
                            if (!destPath.StartsWith(fullTargetDir, StringComparison.OrdinalIgnoreCase))
                            {
                                throw new InvalidDataException("Entrada ZIP invalida fuera del directorio de destino: " + entry.FullName);
                            }

                            if (string.IsNullOrEmpty(entry.Name))
                            {
                                Directory.CreateDirectory(destPath);
                            }
                            else
                            {
                                string parent = Path.GetDirectoryName(destPath);
                                if (!Directory.Exists(parent)) Directory.CreateDirectory(parent);
                                entry.ExtractToFile(destPath, true);
                            }
                            worker.ReportProgress(percent, "Extrayendo: " + entry.Name);
                        }
                    }
                }

                // 2. Instalar plugin SCS
                if (installPlugin && Directory.Exists(atsDir))
                {
                    worker.ReportProgress(75, "Instalando plugin scs-telemetry.dll en American Truck Simulator...");
                    try
                    {
                        string pluginTargetDir = Path.Combine(atsDir, @"bin\win_x64\plugins");
                        if (!Directory.Exists(pluginTargetDir)) Directory.CreateDirectory(pluginTargetDir);
                        string srcPlugin = Path.Combine(targetDir, @"server\plugins\scs-telemetry.dll");
                        string dstPlugin = Path.Combine(pluginTargetDir, "scs-telemetry.dll");
                        if (File.Exists(srcPlugin))
                        {
                            File.Copy(srcPlugin, dstPlugin, true);
                        }
                    }
                    catch (UnauthorizedAccessException)
                    {
                        warnings.Add("No se pudo copiar scs-telemetry.dll en la carpeta del juego porque requiere permisos de Administrador.");
                    }
                    catch (Exception ex)
                    {
                        warnings.Add("No se pudo copiar scs-telemetry.dll en la carpeta del juego (puede requerir permisos de Administrador): " + ex.Message);
                    }
                }

                // 3. Crear Acceso Directo Escritorio
                string launcherExe = Path.Combine(targetDir, "Iniciar_Servidor.exe");
                if (createDesktop && File.Exists(launcherExe))
                {
                    worker.ReportProgress(85, "Creando acceso directo en el Escritorio...");
                    try
                    {
                        string desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
                        string shortcutPath = Path.Combine(desktop, "ATS GPS Companion.lnk");
                        CreateShortcut(shortcutPath, launcherExe, targetDir);
                    }
                    catch { }
                }

                // 4. Crear Acceso Directo Menu Inicio
                if (createStartMenu && File.Exists(launcherExe))
                {
                    worker.ReportProgress(90, "Creando acceso directo en el Menu Inicio...");
                    try
                    {
                        string startMenu = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "ATS GPS");
                        if (!Directory.Exists(startMenu)) Directory.CreateDirectory(startMenu);
                        string shortcutPath = Path.Combine(startMenu, "ATS GPS Companion.lnk");
                        CreateShortcut(shortcutPath, launcherExe, targetDir);
                    }
                    catch { }
                }

                // 5. Configurar Firewall
                if (configureFirewall)
                {
                    worker.ReportProgress(95, "Configurando regla en Firewall de Windows...");
                    try
                    {
                        bool ruleExists = false;
                        ProcessStartInfo showPsi = new ProcessStartInfo("netsh", "advfirewall firewall show rule name=\"ATS GPS Server\"")
                        {
                            CreateNoWindow = true,
                            UseShellExecute = false,
                            RedirectStandardOutput = true,
                            RedirectStandardError = true
                        };
                        using (Process showProc = Process.Start(showPsi))
                        {
                            if (showProc != null)
                            {
                                showProc.WaitForExit(3000);
                                ruleExists = (showProc.ExitCode == 0);
                            }
                        }

                        if (!ruleExists)
                        {
                            ProcessStartInfo psi = new ProcessStartInfo("netsh", "advfirewall firewall add rule name=\"ATS GPS Server\" dir=in action=allow protocol=TCP localport=35500 profile=private remoteip=LocalSubnet")
                            {
                                CreateNoWindow = true,
                                UseShellExecute = false,
                                RedirectStandardOutput = true,
                                RedirectStandardError = true
                            };
                            using (Process p = Process.Start(psi))
                            {
                                if (p != null)
                                {
                                    p.WaitForExit(3000);
                                    if (p.ExitCode != 0)
                                    {
                                        warnings.Add("No se pudo crear la regla de Firewall (TCP 35500); ejecuta el instalador como Administrador o habilitala manualmente.");
                                    }
                                }
                                else
                                {
                                    warnings.Add("No se pudo iniciar netsh para configurar el Firewall de Windows.");
                                }
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        warnings.Add("Error configurando regla de Firewall (puede requerir permisos de Administrador): " + ex.Message);
                    }
                }

                args.Result = warnings;
                worker.ReportProgress(100, "Instalacion finalizada.");
            };

            worker.ProgressChanged += delegate(object s, ProgressChangedEventArgs args)
            {
                progressBar.Value = Math.Min(100, Math.Max(0, args.ProgressPercentage));
                if (args.UserState != null)
                {
                    lblStatus.Text = args.UserState.ToString();
                }
            };

            worker.RunWorkerCompleted += delegate(object s, RunWorkerCompletedEventArgs args)
            {
                if (args.Error != null)
                {
                    MessageBox.Show("Error durante la instalacion:\n" + args.Error.Message, "Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    panelProgress.Visible = false;
                    panelSetup.Visible = true;
                }
                else
                {
                    panelProgress.Visible = false;
                    panelDone.Visible = true;

                    List<string> warnings = args.Result as List<string>;
                    if (warnings != null && warnings.Count > 0)
                    {
                        MessageBox.Show(
                            "La instalacion finalizo con los siguientes avisos:\n\n- " + string.Join("\n- ", warnings),
                            "Avisos de configuracion",
                            MessageBoxButtons.OK,
                            MessageBoxIcon.Warning);
                    }
                }
            };

            worker.RunWorkerAsync();
        }

        private static void CreateShortcut(string shortcutPath, string targetPath, string workingDir)
        {
            try
            {
                Type shellType = Type.GetTypeFromProgID("WScript.Shell");
                dynamic shell = Activator.CreateInstance(shellType);
                dynamic shortcut = shell.CreateShortcut(shortcutPath);
                shortcut.TargetPath = targetPath;
                shortcut.WorkingDirectory = workingDir;
                shortcut.IconLocation = targetPath + ",0";
                shortcut.Description = "Servidor ATS Telemetria GPS";
                shortcut.Save();
            }
            catch { }
        }
    }
}
