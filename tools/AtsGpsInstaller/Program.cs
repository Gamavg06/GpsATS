using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Net.NetworkInformation;
using System.Reflection;
using System.Security.Principal;
using System.Text;
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

    internal sealed class InstallOutcome
    {
        public List<string> Warnings = new List<string>();
        public bool MapBuiltOrVerified;
        public bool MapRuntimeFailed;
        public string MapNote = "";
    }

    public class InstallerForm : Form
    {
        private const string RepoUrl = "https://github.com/Gamavg06/GpsATS";
        private const string DotNetDownloadUrl = "https://dotnet.microsoft.com/download/dotnet/10.0";

        private Panel panelLicense;
        private Button btnTabNotice;
        private Button btnTabLicenses;
        private Button btnTabGpl;
        private TextBox txtLicenseViewer;
        private CheckBox chkAcceptLicense;
        private Button btnContinue;

        private string noticeText = "";
        private string licensesMdText = "";
        private string gplLicenseText = "";

        private TextBox txtInstallDir;
        private TextBox txtAtsDir;
        private CheckBox chkInstallPlugin;
        private CheckBox chkBuildMap;
        private CheckBox chkDesktopShortcut;
        private CheckBox chkStartMenu;
        private CheckBox chkFirewall;
        private CheckBox chkLaunchAfter;
        private Button btnInstall;
        private Panel panelRuntimeWarning;
        private Label lblRuntimeWarning;
        private LinkLabel lnkRuntimeDownload;
        private ProgressBar progressBar;
        private Label lblStatus;
        private Label lblDoneDesc;
        private LinkLabel lnkDoneRuntime;
        private Panel panelSetup;
        private Panel panelProgress;
        private Panel panelDone;
        private BackgroundWorker worker;
        private Process activeMapProc;
        private readonly object activeProcLock = new object();
        private bool hasDesktopRuntime10;
        private bool hasAspNetRuntime10;
        private string missingRuntimesDesc = "";

        private Color bgDark = Color.FromArgb(12, 16, 19);
        private Color panelDark = Color.FromArgb(21, 28, 33);
        private Color viewerDark = Color.FromArgb(7, 10, 13);
        private Color cyanAccent = Color.FromArgb(56, 189, 248);
        private Color limeAccent = Color.FromArgb(199, 243, 107);
        private Color textWhite = Color.FromArgb(241, 245, 249);
        private Color textGray = Color.FromArgb(148, 163, 184);
        private Color borderDark = Color.FromArgb(36, 48, 59);

        public InstallerForm()
        {
            this.Text = "Instalador SGNIA - ATS Telemetria GPS v1.4.2";
            this.Size = new Size(680, 640);
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

            LoadEmbeddedLicenses();
            BuildUI();
            DetectAtsPath();
            CheckDotNet10Runtimes();
            this.FormClosing += InstallerForm_FormClosing;
        }

        private void LoadEmbeddedLicenses()
        {
            noticeText = ReadEmbeddedTextResource("NOTICE");
            licensesMdText = ReadEmbeddedTextResource("LICENSES.md");
            gplLicenseText = ReadEmbeddedTextResource("LICENSE");
        }

        private static string ReadEmbeddedTextResource(string resourceName)
        {
            Assembly asm = Assembly.GetExecutingAssembly();
            Stream stream = asm.GetManifestResourceStream(resourceName);
            if (stream == null)
            {
                string[] names = asm.GetManifestResourceNames();
                foreach (string name in names)
                {
                    if (string.Equals(name, resourceName, StringComparison.OrdinalIgnoreCase) ||
                        name.EndsWith("." + resourceName, StringComparison.OrdinalIgnoreCase))
                    {
                        stream = asm.GetManifestResourceStream(name);
                        break;
                    }
                }
            }

            if (stream == null)
            {
                return "Documento no disponible en los recursos embebidos (" + resourceName + ").\r\n" +
                       "Consulta el repositorio oficial: " + RepoUrl;
            }

            using (stream)
            using (StreamReader reader = new StreamReader(stream, Encoding.UTF8, true))
            {
                string raw = reader.ReadToEnd();
                return raw.Replace("\r\n", "\n").Replace("\n", "\r\n");
            }
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
            subtitle.Text = "Asistente de configuracion, licencias y mapa local v1.4.2";
            subtitle.Font = new Font("Segoe UI", 9.5F, FontStyle.Regular);
            subtitle.ForeColor = textGray;
            subtitle.Location = new Point(25, 48);
            subtitle.AutoSize = true;
            header.Controls.Add(subtitle);

            // Paso 1: Panel Obligatorio de Licencias (visible al iniciar)
            panelLicense = new Panel();
            panelLicense.Location = new Point(24, 100);
            panelLicense.Size = new Size(620, 480);
            panelLicense.Visible = true;
            this.Controls.Add(panelLicense);

            Label lblLicIntro = new Label();
            lblLicIntro.Text = "Paso 1: Revisa las licencias y avisos de terceros antes de instalar";
            lblLicIntro.Font = new Font("Segoe UI", 10F, FontStyle.Bold);
            lblLicIntro.ForeColor = textWhite;
            lblLicIntro.Location = new Point(0, 0);
            lblLicIntro.AutoSize = true;
            panelLicense.Controls.Add(lblLicIntro);

            btnTabNotice = CreateTabButton("Aviso y Terceros (NOTICE)", 0, 28, 202);
            btnTabNotice.Click += delegate { SelectLicenseDocument("NOTICE"); };
            panelLicense.Controls.Add(btnTabNotice);

            btnTabLicenses = CreateTabButton("Resumen Licencias (LICENSES.md)", 209, 28, 202);
            btnTabLicenses.Click += delegate { SelectLicenseDocument("LICENSES.md"); };
            panelLicense.Controls.Add(btnTabLicenses);

            btnTabGpl = CreateTabButton("Licencia GPL-2.0 (LICENSE)", 418, 28, 202);
            btnTabGpl.Click += delegate { SelectLicenseDocument("LICENSE"); };
            panelLicense.Controls.Add(btnTabGpl);

            txtLicenseViewer = new TextBox();
            txtLicenseViewer.Location = new Point(0, 66);
            txtLicenseViewer.Size = new Size(620, 250);
            txtLicenseViewer.Multiline = true;
            txtLicenseViewer.ReadOnly = true;
            txtLicenseViewer.ScrollBars = ScrollBars.Vertical;
            txtLicenseViewer.WordWrap = true;
            txtLicenseViewer.BackColor = viewerDark;
            txtLicenseViewer.ForeColor = Color.FromArgb(203, 213, 225);
            txtLicenseViewer.Font = new Font("Consolas", 8.5F);
            txtLicenseViewer.BorderStyle = BorderStyle.FixedSingle;
            panelLicense.Controls.Add(txtLicenseViewer);

            Label lblSourcePrefix = new Label();
            lblSourcePrefix.Text = "Codigo fuente oficial:";
            lblSourcePrefix.Font = new Font("Segoe UI", 9F, FontStyle.Bold);
            lblSourcePrefix.ForeColor = textGray;
            lblSourcePrefix.Location = new Point(0, 326);
            lblSourcePrefix.AutoSize = true;
            panelLicense.Controls.Add(lblSourcePrefix);

            LinkLabel lnkSource = new LinkLabel();
            lnkSource.Text = RepoUrl;
            lnkSource.Font = new Font("Segoe UI", 9F, FontStyle.Underline);
            lnkSource.LinkColor = cyanAccent;
            lnkSource.ActiveLinkColor = limeAccent;
            lnkSource.VisitedLinkColor = cyanAccent;
            lnkSource.Location = new Point(138, 326);
            lnkSource.AutoSize = true;
            lnkSource.LinkClicked += delegate
            {
                try
                {
                    Process.Start(new ProcessStartInfo(RepoUrl) { UseShellExecute = true });
                }
                catch { }
            };
            panelLicense.Controls.Add(lnkSource);

            chkAcceptLicense = new CheckBox();
            chkAcceptLicense.Text = "Acepto los terminos de GNU GPL-2.0, MIT y los avisos de terceros";
            chkAcceptLicense.Checked = false;
            chkAcceptLicense.ForeColor = textWhite;
            chkAcceptLicense.Font = new Font("Segoe UI", 9.5F, FontStyle.Bold);
            chkAcceptLicense.Location = new Point(0, 354);
            chkAcceptLicense.AutoSize = true;
            chkAcceptLicense.CheckedChanged += delegate
            {
                btnContinue.Enabled = chkAcceptLicense.Checked;
                UpdateContinueButtonStyle();
            };
            panelLicense.Controls.Add(chkAcceptLicense);

            btnContinue = new Button();
            btnContinue.Text = "CONTINUAR";
            btnContinue.Size = new Size(200, 40);
            btnContinue.Location = new Point(210, 390);
            btnContinue.Font = new Font("Segoe UI", 10.5F, FontStyle.Bold);
            btnContinue.FlatStyle = FlatStyle.Flat;
            btnContinue.Cursor = Cursors.Hand;
            btnContinue.Enabled = false;
            btnContinue.Click += delegate
            {
                panelLicense.Visible = false;
                panelSetup.Visible = true;
            };
            panelLicense.Controls.Add(btnContinue);
            UpdateContinueButtonStyle();
            SelectLicenseDocument("NOTICE");

            // Paso 2: Panel Setup Principal (oculto hasta aceptar licencias)
            panelSetup = new Panel();
            panelSetup.Location = new Point(24, 100);
            panelSetup.Size = new Size(620, 480);
            panelSetup.Visible = false;
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

            y += 42;
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

            y += 42;
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
            chkBuildMap = new CheckBox();
            chkBuildMap.Text = "Generar mapa local desde tu instalacion de ATS (AtsGpsServer --build-map)";
            chkBuildMap.Checked = true;
            chkBuildMap.ForeColor = textWhite;
            chkBuildMap.Font = new Font("Segoe UI", 9F);
            chkBuildMap.Location = new Point(5, y);
            chkBuildMap.AutoSize = true;
            panelSetup.Controls.Add(chkBuildMap);

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

            y += 42;
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

            panelRuntimeWarning = new Panel();
            panelRuntimeWarning.Location = new Point(0, y + 52);
            panelRuntimeWarning.Size = new Size(620, 106);
            panelRuntimeWarning.BackColor = Color.FromArgb(46, 30, 12);
            panelRuntimeWarning.BorderStyle = BorderStyle.FixedSingle;
            panelRuntimeWarning.Visible = false;
            panelSetup.Controls.Add(panelRuntimeWarning);

            lblRuntimeWarning = new Label();
            lblRuntimeWarning.Location = new Point(10, 8);
            lblRuntimeWarning.Size = new Size(598, 66);
            lblRuntimeWarning.Font = new Font("Segoe UI", 8.5F, FontStyle.Bold);
            lblRuntimeWarning.ForeColor = Color.FromArgb(253, 224, 71);
            panelRuntimeWarning.Controls.Add(lblRuntimeWarning);

            lnkRuntimeDownload = new LinkLabel();
            lnkRuntimeDownload.Text = "Descargar .NET 10 Runtime: " + DotNetDownloadUrl;
            lnkRuntimeDownload.Font = new Font("Segoe UI", 8.5F, FontStyle.Underline);
            lnkRuntimeDownload.LinkColor = cyanAccent;
            lnkRuntimeDownload.ActiveLinkColor = limeAccent;
            lnkRuntimeDownload.VisitedLinkColor = cyanAccent;
            lnkRuntimeDownload.Location = new Point(10, 78);
            lnkRuntimeDownload.AutoSize = true;
            lnkRuntimeDownload.LinkClicked += delegate
            {
                OpenUrl(DotNetDownloadUrl);
            };
            panelRuntimeWarning.Controls.Add(lnkRuntimeDownload);

            // Panel Progreso
            panelProgress = new Panel();
            panelProgress.Location = new Point(24, 100);
            panelProgress.Size = new Size(620, 480);
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
            lblStatus.Size = new Size(610, 42);
            panelProgress.Controls.Add(lblStatus);

            progressBar = new ProgressBar();
            progressBar.Location = new Point(0, 145);
            progressBar.Size = new Size(610, 28);
            progressBar.Style = ProgressBarStyle.Continuous;
            panelProgress.Controls.Add(progressBar);

            // Panel Completado
            panelDone = new Panel();
            panelDone.Location = new Point(24, 100);
            panelDone.Size = new Size(620, 480);
            panelDone.Visible = false;
            this.Controls.Add(panelDone);

            Label lblDoneTitle = new Label();
            lblDoneTitle.Text = "Instalacion completada con exito";
            lblDoneTitle.Font = new Font("Segoe UI", 15F, FontStyle.Bold);
            lblDoneTitle.ForeColor = limeAccent;
            lblDoneTitle.Location = new Point(0, 24);
            lblDoneTitle.AutoSize = true;
            panelDone.Controls.Add(lblDoneTitle);

            lblDoneDesc = new Label();
            lblDoneDesc.Text = "Todos los componentes del servidor y la telemetria estan listos.\n\nPuedes conectar tu celular por cable USB o Wi-Fi e instalar la aplicacion movil desde el panel de control.";
            lblDoneDesc.Font = new Font("Segoe UI", 9.5F);
            lblDoneDesc.ForeColor = textWhite;
            lblDoneDesc.Location = new Point(0, 68);
            lblDoneDesc.Size = new Size(610, 155);
            panelDone.Controls.Add(lblDoneDesc);

            lnkDoneRuntime = new LinkLabel();
            lnkDoneRuntime.Text = "Descargar .NET 10 Desktop y ASP.NET Core Runtime: " + DotNetDownloadUrl;
            lnkDoneRuntime.Font = new Font("Segoe UI", 9F, FontStyle.Underline);
            lnkDoneRuntime.LinkColor = cyanAccent;
            lnkDoneRuntime.ActiveLinkColor = limeAccent;
            lnkDoneRuntime.VisitedLinkColor = cyanAccent;
            lnkDoneRuntime.Location = new Point(0, 230);
            lnkDoneRuntime.AutoSize = true;
            lnkDoneRuntime.Visible = false;
            lnkDoneRuntime.LinkClicked += delegate
            {
                OpenUrl(DotNetDownloadUrl);
            };
            panelDone.Controls.Add(lnkDoneRuntime);

            chkLaunchAfter = new CheckBox();
            chkLaunchAfter.Text = "Abrir ATS Telemetria GPS Server ahora";
            chkLaunchAfter.Checked = true;
            chkLaunchAfter.ForeColor = cyanAccent;
            chkLaunchAfter.Font = new Font("Segoe UI", 10F, FontStyle.Bold);
            chkLaunchAfter.Location = new Point(5, 265);
            chkLaunchAfter.AutoSize = true;
            panelDone.Controls.Add(chkLaunchAfter);

            Button btnFinish = new Button();
            btnFinish.Text = "FINALIZAR";
            btnFinish.Size = new Size(180, 40);
            btnFinish.Location = new Point(210, 315);
            btnFinish.BackColor = limeAccent;
            btnFinish.ForeColor = Color.FromArgb(10, 20, 28);
            btnFinish.Font = new Font("Segoe UI", 11F, FontStyle.Bold);
            btnFinish.FlatStyle = FlatStyle.Flat;
            btnFinish.Cursor = Cursors.Hand;
            btnFinish.Click += delegate
            {
                if (chkLaunchAfter.Checked)
                {
                    string installDir = txtInstallDir.Text.Trim();
                    string exePath = Path.Combine(installDir, "Iniciar_Servidor.exe");
                    if (!string.IsNullOrEmpty(installDir) && File.Exists(exePath))
                    {
                        Process.Start(new ProcessStartInfo(exePath)
                        {
                            WorkingDirectory = installDir
                        });
                    }
                }
                Application.Exit();
            };
            panelDone.Controls.Add(btnFinish);
        }

        private Button CreateTabButton(string text, int x, int y, int width)
        {
            Button btn = new Button();
            btn.Text = text;
            btn.Location = new Point(x, y);
            btn.Size = new Size(width, 30);
            btn.BackColor = panelDark;
            btn.ForeColor = textGray;
            btn.Font = new Font("Segoe UI", 8.5F, FontStyle.Bold);
            btn.FlatStyle = FlatStyle.Flat;
            btn.FlatAppearance.BorderColor = borderDark;
            btn.Cursor = Cursors.Hand;
            return btn;
        }

        private void SelectLicenseDocument(string docName)
        {
            SetTabActive(btnTabNotice, docName == "NOTICE");
            SetTabActive(btnTabLicenses, docName == "LICENSES.md");
            SetTabActive(btnTabGpl, docName == "LICENSE");

            if (docName == "NOTICE")
            {
                txtLicenseViewer.Text = noticeText;
            }
            else if (docName == "LICENSES.md")
            {
                txtLicenseViewer.Text = licensesMdText;
            }
            else
            {
                txtLicenseViewer.Text = gplLicenseText;
            }

            txtLicenseViewer.SelectionStart = 0;
            txtLicenseViewer.SelectionLength = 0;
            txtLicenseViewer.ScrollToCaret();
        }

        private void SetTabActive(Button btn, bool active)
        {
            if (active)
            {
                btn.BackColor = Color.FromArgb(15, 36, 50);
                btn.ForeColor = cyanAccent;
                btn.FlatAppearance.BorderColor = cyanAccent;
            }
            else
            {
                btn.BackColor = panelDark;
                btn.ForeColor = textGray;
                btn.FlatAppearance.BorderColor = borderDark;
            }
        }

        private void UpdateContinueButtonStyle()
        {
            if (btnContinue.Enabled)
            {
                btnContinue.BackColor = cyanAccent;
                btnContinue.ForeColor = Color.FromArgb(10, 20, 28);
            }
            else
            {
                btnContinue.BackColor = panelDark;
                btnContinue.ForeColor = textGray;
            }
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

        private static void OpenUrl(string url)
        {
            try
            {
                Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            }
            catch { }
        }

        private void CheckDotNet10Runtimes()
        {
            hasDesktopRuntime10 = HasRuntimeVersion10("Microsoft.WindowsDesktop.App");
            hasAspNetRuntime10 = HasRuntimeVersion10("Microsoft.AspNetCore.App");

            if (!hasDesktopRuntime10 || !hasAspNetRuntime10)
            {
                List<string> missing = new List<string>();
                if (!hasDesktopRuntime10) missing.Add(".NET 10 Desktop Runtime (Microsoft.WindowsDesktop.App 10.x)");
                if (!hasAspNetRuntime10) missing.Add("ASP.NET Core 10 Runtime (Microsoft.AspNetCore.App 10.x)");
                missingRuntimesDesc = "Falta " + string.Join(" y ", missing.ToArray()) + ".";

                lblRuntimeWarning.Text = "ADVERTENCIA DE REQUISITO (.NET 10):\r\n" +
                                         missingRuntimesDesc + "\r\n" +
                                         "Instala ambos runtimes antes de ejecutar el servidor o generar el mapa.";
                panelRuntimeWarning.Visible = true;
            }
            else
            {
                missingRuntimesDesc = "";
                panelRuntimeWarning.Visible = false;
            }
        }

        private static bool HasRuntimeVersion10(string runtimeName)
        {
            List<string> dotnetRoots = new List<string>();
            string programFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
            if (!string.IsNullOrEmpty(programFiles))
            {
                dotnetRoots.Add(Path.Combine(programFiles, "dotnet"));
            }

            string programW6432 = Environment.GetEnvironmentVariable("ProgramW6432");
            if (!string.IsNullOrEmpty(programW6432))
            {
                dotnetRoots.Add(Path.Combine(programW6432, "dotnet"));
            }

            string dotnetRoot = Environment.GetEnvironmentVariable("DOTNET_ROOT");
            if (!string.IsNullOrEmpty(dotnetRoot))
            {
                dotnetRoots.Add(dotnetRoot);
            }

            foreach (string root in dotnetRoots)
            {
                try
                {
                    string sharedRuntimeDir = Path.Combine(root, Path.Combine("shared", runtimeName));
                    if (Directory.Exists(sharedRuntimeDir))
                    {
                        string[] dirs = Directory.GetDirectories(sharedRuntimeDir, "10.*");
                        foreach (string dir in dirs)
                        {
                            if (Directory.GetFiles(dir).Length > 0)
                            {
                                return true;
                            }
                        }
                    }
                }
                catch { }
            }

            try
            {
                ProcessStartInfo psi = new ProcessStartInfo("dotnet", "--list-runtimes")
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
                        string output = p.StandardOutput.ReadToEnd();
                        p.WaitForExit(3000);
                        string pattern = Regex.Escape(runtimeName) + @"\s+10\.";
                        if (Regex.IsMatch(output, pattern, RegexOptions.IgnoreCase))
                        {
                            return true;
                        }
                    }
                }
            }
            catch { }

            return false;
        }

        private static bool IsRunningAsAdministrator()
        {
            try
            {
                using (WindowsIdentity identity = WindowsIdentity.GetCurrent())
                {
                    WindowsPrincipal principal = new WindowsPrincipal(identity);
                    return principal.IsInRole(WindowsBuiltInRole.Administrator);
                }
            }
            catch
            {
                return false;
            }
        }

        private static bool IsProtectedSystemDirectory(string fullPath)
        {
            string normalized = fullPath.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            List<string> protectedRoots = new List<string>();

            string pf = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
            if (!string.IsNullOrEmpty(pf)) protectedRoots.Add(pf);

            string pf86 = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);
            if (!string.IsNullOrEmpty(pf86)) protectedRoots.Add(pf86);

            string pf64 = Environment.GetEnvironmentVariable("ProgramW6432");
            if (!string.IsNullOrEmpty(pf64)) protectedRoots.Add(pf64);

            string winDir = Environment.GetFolderPath(Environment.SpecialFolder.Windows);
            if (!string.IsNullOrEmpty(winDir)) protectedRoots.Add(winDir);

            foreach (string root in protectedRoots)
            {
                string cleanRoot = root.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
                if (string.Equals(normalized, cleanRoot, StringComparison.OrdinalIgnoreCase) ||
                    normalized.StartsWith(cleanRoot + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                {
                    return true;
                }
            }
            return false;
        }

        private static bool IsPublicFirewallProfileActive()
        {
            try
            {
                Type fwType = Type.GetTypeFromProgID("HNetCfg.FwPolicy2");
                if (fwType == null) return false;
                dynamic fwPolicy = Activator.CreateInstance(fwType);
                int currentProfiles = (int)fwPolicy.CurrentProfileTypes;
                // NET_FW_PROFILE2_PUBLIC = 4
                return (currentProfiles & 4) != 0;
            }
            catch
            {
                return false;
            }
        }

        private static bool HasActiveWifiInterface()
        {
            try
            {
                foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus == OperationalStatus.Up &&
                        ni.NetworkInterfaceType == NetworkInterfaceType.Wireless80211)
                    {
                        return true;
                    }
                }
            }
            catch { }
            return false;
        }

        private void InstallerForm_FormClosing(object sender, FormClosingEventArgs e)
        {
            lock (activeProcLock)
            {
                if (activeMapProc != null)
                {
                    try
                    {
                        if (!activeMapProc.HasExited)
                        {
                            activeMapProc.Kill();
                            activeMapProc.WaitForExit(2000);
                        }
                    }
                    catch { }
                }
            }
        }

        private void BtnInstall_Click(object sender, EventArgs e)
        {
            string targetDir = txtInstallDir.Text.Trim();
            string atsDir = txtAtsDir.Text.Trim();

            if (string.IsNullOrEmpty(targetDir))
            {
                MessageBox.Show(
                    "La carpeta de instalacion no puede estar vacia.\n\nPor favor indica una ruta valida (por ejemplo C:\\Telemetriagps).",
                    "Carpeta de instalacion requerida",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
                txtInstallDir.Focus();
                return;
            }

            string validatedFullTargetDir;
            try
            {
                validatedFullTargetDir = Path.GetFullPath(targetDir);
            }
            catch (Exception ex)
            {
                MessageBox.Show(
                    "La ruta de instalacion no es valida: " + ex.Message,
                    "Ruta invalida",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
                txtInstallDir.Focus();
                return;
            }

            if (IsProtectedSystemDirectory(validatedFullTargetDir) && !IsRunningAsAdministrator())
            {
                string localAppFallback = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AtsGps");
                DialogResult dr = MessageBox.Show(
                    "La ruta seleccionada (" + validatedFullTargetDir + ") requiere permisos de Administrador para escribir archivos y generar el mapa local.\n\n" +
                    "Se recomienda elegir una carpeta escribible como:\n" +
                    "  C:\\Telemetriagps\n" +
                    "  " + localAppFallback + "\n\n" +
                    "Deseas continuar de todos modos con la ruta actual?",
                    "Permisos requeridos para Program Files",
                    MessageBoxButtons.YesNo,
                    MessageBoxIcon.Warning);
                if (dr != DialogResult.Yes)
                {
                    txtInstallDir.Focus();
                    return;
                }
            }

            txtInstallDir.Text = targetDir;
            txtAtsDir.Text = atsDir;
            CheckDotNet10Runtimes();

            panelSetup.Visible = false;
            panelProgress.Visible = true;

            bool installPlugin = chkInstallPlugin.Checked;
            bool buildMap = chkBuildMap.Checked;
            bool createDesktop = chkDesktopShortcut.Checked;
            bool createStartMenu = chkStartMenu.Checked;
            bool configureFirewall = chkFirewall.Checked;

            worker = new BackgroundWorker();
            worker.WorkerReportsProgress = true;
            worker.DoWork += delegate(object s, DoWorkEventArgs args)
            {
                InstallOutcome outcome = new InstallOutcome();
                if (!hasDesktopRuntime10 || !hasAspNetRuntime10)
                {
                    outcome.Warnings.Add(missingRuntimesDesc + " Descargalo en " + DotNetDownloadUrl);
                }

                // 1. Extraer payload.zip (sin map_ats.bin, con LICENSE, LICENSES.md y NOTICE)
                worker.ReportProgress(10, "Extrayendo archivos del servidor y avisos legales...");
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
                            int percent = 10 + (count * 55 / total);
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
                    worker.ReportProgress(68, "Instalando plugin scs-telemetry.dll en American Truck Simulator...");
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
                        outcome.Warnings.Add("No se pudo copiar scs-telemetry.dll en la carpeta del juego porque requiere permisos de Administrador.");
                    }
                    catch (Exception ex)
                    {
                        outcome.Warnings.Add("No se pudo copiar scs-telemetry.dll en la carpeta del juego (puede requerir permisos de Administrador): " + ex.Message);
                    }
                }

                // 3. Crear Acceso Directo Escritorio
                string launcherExe = Path.Combine(targetDir, "Iniciar_Servidor.exe");
                if (createDesktop && File.Exists(launcherExe))
                {
                    worker.ReportProgress(70, "Creando acceso directo en el Escritorio...");
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
                    worker.ReportProgress(72, "Creando acceso directo en el Menu Inicio...");
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
                    worker.ReportProgress(74, "Configurando regla en Firewall de Windows...");
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

                        string netshArgs = ruleExists
                            ? "advfirewall firewall set rule name=\"ATS GPS Server\" new profile=private,domain remoteip=LocalSubnet"
                            : "advfirewall firewall add rule name=\"ATS GPS Server\" dir=in action=allow protocol=TCP localport=35500 profile=private,domain remoteip=LocalSubnet";

                        ProcessStartInfo psi = new ProcessStartInfo("netsh", netshArgs)
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
                                if (p.ExitCode != 0 && !ruleExists)
                                {
                                    outcome.Warnings.Add("No se pudo crear la regla de Firewall (TCP 35500); ejecuta el instalador como Administrador o habilitala manualmente.");
                                }
                            }
                            else if (!ruleExists)
                            {
                                outcome.Warnings.Add("No se pudo iniciar netsh para configurar el Firewall de Windows.");
                            }
                        }

                        if (IsPublicFirewallProfileActive())
                        {
                            if (HasActiveWifiInterface())
                            {
                                outcome.Warnings.Add("Tu red Wi-Fi actual esta en perfil Publico. La regla TCP 35500 aplica a perfiles Privado y Dominio; cambia la red Wi-Fi a perfil Privado en Configuracion de Windows para conectar el celular por Wi-Fi.");
                            }
                            else
                            {
                                outcome.Warnings.Add("Se detecto un perfil de red Publico activo en el Firewall de Windows. La regla TCP 35500 aplica a perfiles Privado y Dominio; si usas Wi-Fi, cambia la red a perfil Privado.");
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        outcome.Warnings.Add("Error configurando regla de Firewall (puede requerir permisos de Administrador): " + ex.Message);
                    }
                }

                // 6. Generacion local del mapa desde los archivos propios de ATS
                if (buildMap && Directory.Exists(atsDir))
                {
                    string serverPublishDir = Path.Combine(targetDir, @"server\publish");
                    string serverExe = Path.Combine(serverPublishDir, "AtsGpsServer.exe");

                    if (!File.Exists(serverExe))
                    {
                        outcome.Warnings.Add("No se encontro AtsGpsServer.exe para generar el mapa local; se generara en el primer arranque del servidor.");
                        outcome.MapNote = "Nota del mapa: map_ats.bin se generara localmente en el primer arranque del servidor (no se redistribuyen datos del juego).";
                    }
                    else
                    {
                        worker.ReportProgress(75, "Generando mapa local desde ATS (AtsGpsServer --build-map)...");
                        int currentPct = 75;
                        object progressLock = new object();
                        bool alreadyMatched = false;
                        StringBuilder stderrBuilder = new StringBuilder();
                        List<string> stdoutTail = new List<string>();
                        string cleanAtsDir = atsDir.TrimEnd('\\', '/');
                        if (cleanAtsDir.Length == 2 && cleanAtsDir[1] == ':')
                        {
                            cleanAtsDir += "\\\\";
                        }

                        try
                        {
                            ProcessStartInfo mapPsi = new ProcessStartInfo
                            {
                                FileName = serverExe,
                                Arguments = "--build-map --ats-dir \"" + cleanAtsDir + "\"",
                                WorkingDirectory = serverPublishDir,
                                RedirectStandardOutput = true,
                                RedirectStandardError = true,
                                StandardOutputEncoding = Encoding.UTF8,
                                StandardErrorEncoding = Encoding.UTF8,
                                UseShellExecute = false,
                                CreateNoWindow = true
                            };

                            using (Process mapProc = new Process { StartInfo = mapPsi })
                            {
                                mapProc.OutputDataReceived += delegate(object senderObj, DataReceivedEventArgs ev)
                                {
                                    if (string.IsNullOrWhiteSpace(ev.Data)) return;
                                    string line = ev.Data.Trim();

                                    lock (progressLock)
                                    {
                                        stdoutTail.Add(line);
                                        if (stdoutTail.Count > 12)
                                        {
                                            stdoutTail.RemoveAt(0);
                                        }

                                        if (line.IndexOf("ya existe y coincide", StringComparison.OrdinalIgnoreCase) >= 0 ||
                                            line.IndexOf("Omitiendo regeneracion", StringComparison.OrdinalIgnoreCase) >= 0)
                                        {
                                            alreadyMatched = true;
                                            currentPct = 98;
                                            worker.ReportProgress(98, line);
                                            return;
                                        }

                                        Match m = Regex.Match(line, @"\[(\d+)/(\d+)\]");
                                        if (m.Success)
                                        {
                                            int cur;
                                            int tot;
                                            if (int.TryParse(m.Groups[1].Value, out cur) &&
                                                int.TryParse(m.Groups[2].Value, out tot) &&
                                                tot > 0)
                                            {
                                                int pct = 75 + (int)Math.Round((cur * 23.0) / tot);
                                                if (pct < 75) pct = 75;
                                                if (pct > 98) pct = 98;
                                                if (pct > currentPct) currentPct = pct;
                                            }
                                            worker.ReportProgress(currentPct, line);
                                        }
                                        else if (line.IndexOf("Extraccion completada", StringComparison.OrdinalIgnoreCase) >= 0 ||
                                                 line.IndexOf("Archivo guardado", StringComparison.OrdinalIgnoreCase) >= 0)
                                        {
                                            currentPct = 98;
                                            worker.ReportProgress(98, line);
                                        }
                                    }
                                };

                                mapProc.ErrorDataReceived += delegate(object senderObj, DataReceivedEventArgs ev)
                                {
                                    if (!string.IsNullOrWhiteSpace(ev.Data))
                                    {
                                        lock (progressLock)
                                        {
                                            stderrBuilder.AppendLine(ev.Data.Trim());
                                        }
                                    }
                                };

                                lock (activeProcLock)
                                {
                                    activeMapProc = mapProc;
                                }
                                try
                                {
                                    mapProc.Start();
                                    mapProc.BeginOutputReadLine();
                                    mapProc.BeginErrorReadLine();
                                    mapProc.WaitForExit();
                                }
                                finally
                                {
                                    lock (activeProcLock)
                                    {
                                        activeMapProc = null;
                                    }
                                }

                                if (mapProc.ExitCode == 0)
                                {
                                    outcome.MapBuiltOrVerified = true;
                                    outcome.MapNote = alreadyMatched
                                        ? "Mapa local verificado: map_ats.bin ya coincide con la version del extractor (ATSMAP03 v3)."
                                        : "Mapa local generado correctamente en tu equipo a partir de tu instalacion de ATS.";
                                }
                                else
                                {
                                    string stderrText = stderrBuilder.ToString().Trim();
                                    string stdoutText;
                                    lock (progressLock)
                                    {
                                        int takeCount = Math.Min(5, stdoutTail.Count);
                                        int startIdx = stdoutTail.Count - takeCount;
                                        List<string> lastLines = new List<string>();
                                        for (int i = startIdx; i < stdoutTail.Count; i++)
                                        {
                                            lastLines.Add(stdoutTail[i]);
                                        }
                                        stdoutText = string.Join(" | ", lastLines.ToArray());
                                    }

                                    string errDetail = "";
                                    if (!string.IsNullOrEmpty(stderrText) && !string.IsNullOrEmpty(stdoutText))
                                    {
                                        errDetail = stderrText + " | " + stdoutText;
                                    }
                                    else if (!string.IsNullOrEmpty(stderrText))
                                    {
                                        errDetail = stderrText;
                                    }
                                    else if (!string.IsNullOrEmpty(stdoutText))
                                    {
                                        errDetail = stdoutText;
                                    }

                                    bool runtimeError = (!hasDesktopRuntime10 || !hasAspNetRuntime10) ||
                                        errDetail.IndexOf("You must install", StringComparison.OrdinalIgnoreCase) >= 0 ||
                                        errDetail.IndexOf("Microsoft.AspNetCore.App", StringComparison.OrdinalIgnoreCase) >= 0 ||
                                        errDetail.IndexOf("Microsoft.NETCore.App", StringComparison.OrdinalIgnoreCase) >= 0 ||
                                        errDetail.IndexOf("hostfxr", StringComparison.OrdinalIgnoreCase) >= 0 ||
                                        errDetail.IndexOf("framework", StringComparison.OrdinalIgnoreCase) >= 0;

                                    if (runtimeError)
                                    {
                                        outcome.MapRuntimeFailed = true;
                                    }

                                    string warnMsg = "La generacion del mapa local termino con codigo " + mapProc.ExitCode +
                                                     ". Podras generarlo en el primer arranque del servidor.";
                                    if (!string.IsNullOrEmpty(errDetail))
                                    {
                                        warnMsg += " (" + errDetail + ")";
                                    }
                                    outcome.Warnings.Add(warnMsg);
                                    outcome.MapNote = runtimeError
                                        ? "Error de runtime .NET 10 durante --build-map: " + (!string.IsNullOrEmpty(missingRuntimesDesc) ? missingRuntimesDesc : "Falta .NET 10 Runtime.")
                                        : "Nota del mapa: No se completo la extraccion ahora; el mapa se generara localmente en el primer arranque del servidor (no se redistribuyen datos del juego).";
                                }
                            }
                        }
                        catch (Exception ex)
                        {
                            outcome.Warnings.Add("No se pudo ejecutar AtsGpsServer --build-map (" + ex.Message + "). Puedes generarlo en el primer arranque del servidor.");
                            outcome.MapNote = "Nota del mapa: El mapa se generara localmente en el primer arranque del servidor (no se redistribuyen datos del juego).";
                        }
                    }
                }
                else
                {
                    outcome.MapNote = "Nota del mapa: El mapa local (map_ats.bin) se generara automaticamente en el primer arranque del servidor desde tu instalacion de ATS (no se redistribuyen datos del juego).";
                }

                args.Result = outcome;
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
                    InstallOutcome outcome = args.Result as InstallOutcome;
                    string baseDone = "Todos los componentes del servidor y las licencias estan listos.\n\n" +
                                      "Puedes conectar tu celular por cable USB (requiere Platform-Tools opcional) o por Wi-Fi e ingresar el PIN de 6 digitos mostrado en el servidor.";
                    if (outcome != null && !string.IsNullOrEmpty(outcome.MapNote))
                    {
                        lblDoneDesc.Text = baseDone + "\n\n" + outcome.MapNote;
                    }
                    else
                    {
                        lblDoneDesc.Text = baseDone;
                    }

                    bool showRuntimeLink = (!hasDesktopRuntime10 || !hasAspNetRuntime10) ||
                                           (outcome != null && outcome.MapRuntimeFailed);
                    lnkDoneRuntime.Visible = showRuntimeLink;
                    if (showRuntimeLink && !string.IsNullOrEmpty(missingRuntimesDesc) &&
                        lblDoneDesc.Text.IndexOf(missingRuntimesDesc, StringComparison.OrdinalIgnoreCase) < 0)
                    {
                        lblDoneDesc.Text += "\n\nRequisito pendiente: " + missingRuntimesDesc;
                    }

                    panelProgress.Visible = false;
                    panelDone.Visible = true;

                    if (outcome != null && outcome.Warnings.Count > 0)
                    {
                        MessageBox.Show(
                            "La instalacion finalizo con los siguientes avisos:\n\n- " + string.Join("\n- ", outcome.Warnings),
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
