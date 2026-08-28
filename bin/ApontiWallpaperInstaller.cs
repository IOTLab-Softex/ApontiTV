using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

namespace ApontiWallpaperInstaller
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
        const string TaskName = "ApontiTV Wallpaper Agent";
        const string AgentVersion = "1.3.1";
        static readonly string InstallDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "ApontiTV", "WallpaperAgent");
        static readonly string AgentPath = Path.Combine(InstallDir, "aponti_wallpaper_agent.ps1");
        static readonly string ConfigPath = Path.Combine(InstallDir, "agent.json");
        static readonly string LogPath = Path.Combine(InstallDir, "agent.log");
        static readonly string StartupScriptPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.Startup),
            "ApontiTVWallpaperAgent.vbs"
        );

        RadioButton localMode;
        RadioButton publicMode;
        TextBox localUrl;
        TextBox publicUrl;
        TextBox computerName;
        NumericUpDown interval;
        Label status;
        Label approvalCode;

        string currentToken = "";
        string pairingSecret = "";
        string pairingId = "";

        public InstallerForm()
        {
            Text = "Aponti TV - Agente de Wallpaper";
            StartPosition = FormStartPosition.CenterScreen;
            MinimumSize = new Size(740, 590);
            Size = new Size(780, 640);
            BackColor = Color.FromArgb(7, 12, 26);
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 10F);
            BuildUi();
            LoadConfig();
        }

        void BuildUi()
        {
            var root = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(28), RowCount = 6, ColumnCount = 1 };
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 86));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 58));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 214));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 76));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 70));
            Controls.Add(root);

            root.Controls.Add(new Label { Text = "Aponti TV", Font = new Font("Segoe UI", 24F, FontStyle.Bold), Dock = DockStyle.Fill, TextAlign = ContentAlignment.BottomLeft }, 0, 0);
            root.Controls.Add(new Label { Text = "O computador solicita acesso ao servidor. Depois de aprovado, recebe o token automaticamente.", ForeColor = Color.FromArgb(174, 185, 205), Dock = DockStyle.Fill }, 0, 1);

            var configBox = Card("Conexao e aprovacao");
            configBox.RowCount = 5;
            for (var i = 0; i < 5; i++) configBox.RowStyles.Add(new RowStyle(SizeType.Absolute, 40));
            root.Controls.Add(configBox, 0, 2);

            var modePanel = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight, WrapContents = false, BackColor = Color.Transparent };
            localMode = new RadioButton { Text = "Local", Checked = true, AutoSize = true, ForeColor = Color.White, Margin = new Padding(0, 8, 28, 0) };
            publicMode = new RadioButton { Text = "Publico", AutoSize = true, ForeColor = Color.White, Margin = new Padding(0, 8, 0, 0) };
            modePanel.Controls.Add(localMode);
            modePanel.Controls.Add(publicMode);
            configBox.Controls.Add(modePanel, 0, 0);

            localUrl = Input("http://192.168.1.98:3000");
            publicUrl = Input("http://apontitv.com");
            computerName = Input(Environment.MachineName);
            approvalCode = new Label { Text = "Codigo: aguardando solicitacao", Dock = DockStyle.Fill, ForeColor = Color.FromArgb(196, 181, 253), TextAlign = ContentAlignment.MiddleLeft };
            configBox.Controls.Add(Labeled("Servidor local", localUrl), 0, 1);
            configBox.Controls.Add(Labeled("Servidor publico", publicUrl), 0, 2);
            configBox.Controls.Add(Labeled("Nome do computador", computerName), 0, 3);
            configBox.Controls.Add(approvalCode, 0, 4);

            var optionsBox = Card("Execucao em segundo plano");
            optionsBox.RowCount = 1;
            optionsBox.RowStyles.Add(new RowStyle(SizeType.Absolute, 50));
            root.Controls.Add(optionsBox, 0, 3);

            interval = new NumericUpDown { Minimum = 5, Maximum = 3600, Value = 5, Width = 120, BackColor = Color.FromArgb(11, 18, 36), ForeColor = Color.White };
            optionsBox.Controls.Add(Labeled("Verificar a cada segundos", interval), 0, 0);

            status = new Label { Dock = DockStyle.Fill, ForeColor = Color.FromArgb(174, 185, 205), Text = "Clique em Conectar. Depois aprove este computador no painel Agentes Windows.", TextAlign = ContentAlignment.MiddleLeft };
            root.Controls.Add(status, 0, 4);

            var buttons = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.RightToLeft, WrapContents = false, BackColor = Color.Transparent };
            root.Controls.Add(buttons, 0, 5);
            buttons.Controls.Add(Button("Conectar", PairAndInstall));
            buttons.Controls.Add(Button("Testar", TestNow));
            buttons.Controls.Add(Button("Iniciar", StartTask));
            buttons.Controls.Add(Button("Parar", StopTask));
            buttons.Controls.Add(Button("Logs", OpenLogs));
        }

        TableLayoutPanel Card(string title)
        {
            var panel = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(18, 8, 18, 8), BackColor = Color.FromArgb(16, 24, 44), ColumnCount = 1 };
            panel.Controls.Add(new Label { Text = title, ForeColor = Color.White, Font = new Font("Segoe UI", 12F, FontStyle.Bold), Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft }, 0, 0);
            return panel;
        }

        Control Labeled(string label, Control input)
        {
            var panel = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, BackColor = Color.Transparent };
            panel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 190));
            panel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            panel.Controls.Add(new Label { Text = label, Dock = DockStyle.Fill, ForeColor = Color.FromArgb(214, 222, 238), TextAlign = ContentAlignment.MiddleLeft }, 0, 0);
            panel.Controls.Add(input, 1, 0);
            return panel;
        }

        TextBox Input(string value)
        {
            return new TextBox { Text = value, Dock = DockStyle.Fill, BackColor = Color.FromArgb(11, 18, 36), ForeColor = Color.White, BorderStyle = BorderStyle.FixedSingle };
        }

        Button Button(string text, EventHandler handler)
        {
            var button = new Button { Text = text, Width = 122, Height = 40, Margin = new Padding(8, 12, 0, 0), BackColor = Color.FromArgb(139, 47, 242), ForeColor = Color.White, FlatStyle = FlatStyle.Flat };
            button.FlatAppearance.BorderSize = 0;
            button.Click += handler;
            return button;
        }

        void LoadConfig()
        {
            try
            {
                if (!File.Exists(ConfigPath)) return;
                var json = File.ReadAllText(ConfigPath);
                localUrl.Text = JsonValue(json, "local_url", localUrl.Text);
                publicUrl.Text = JsonValue(json, "public_url", publicUrl.Text);
                computerName.Text = JsonValue(json, "name", computerName.Text);
                currentToken = JsonValue(json, "token", "");
                pairingSecret = JsonValue(json, "pairing_secret", "");
                pairingId = JsonValue(json, "pairing_id", "");
                interval.Value = Math.Max(interval.Minimum, Math.Min(interval.Maximum, NumberValue(json, "interval_seconds", 5)));
                publicMode.Checked = JsonValue(json, "mode") == "public";
                localMode.Checked = !publicMode.Checked;
                status.Text = currentToken.Length > 0 ? "Agente ja aprovado. Voce pode alterar o servidor e clicar em Conectar." : "Configuracao carregada. Ainda aguardando aprovacao.";
            }
            catch (Exception ex) { status.Text = "Nao foi possivel ler configuracao: " + ex.Message; }
        }

        void PairAndInstall(object sender, EventArgs e)
        {
            try
            {
                if (SelectedServerUrl().Length == 0) throw new Exception("Informe o servidor.");
                if (computerName.Text.Trim().Length == 0) throw new Exception("Informe o nome do computador.");

                Directory.CreateDirectory(InstallDir);
                File.WriteAllText(AgentPath, AgentScript(), Encoding.UTF8);

                if (pairingSecret.Length == 0) pairingSecret = RandomHex(32);
                var created = PostPairing();
                pairingId = JsonValue(created, "id");
                var code = JsonValue(created, "approval_code");
                var immediateToken = JsonValue(created, "token");
                approvalCode.Text = "Codigo: " + code;

                if (immediateToken.Length > 0)
                {
                    currentToken = immediateToken;
                }
                else
                {
                    currentToken = "";
                }

                if (currentToken.Length == 0)
                {
                    SaveConfig(currentToken);

                    status.Text = "Solicitacao enviada. Aprove no Aponti TV em Agentes Windows. Aguardando...";
                    Application.DoEvents();

                    var deadline = DateTime.Now.AddMinutes(5);
                    while (DateTime.Now < deadline && currentToken.Length == 0)
                    {
                        Thread.Sleep(3000);
                        Application.DoEvents();
                        var check = GetPairingStatus();
                        var token = JsonValue(check, "token");
                        if (token.Length > 0)
                        {
                            currentToken = token;
                            break;
                        }
                        status.Text = "Aguardando aprovacao no servidor... Codigo: " + code;
                    }

                    if (currentToken.Length == 0) throw new Exception("Tempo de aprovacao esgotado. Aprove no painel e clique em Conectar de novo.");
                }

                SaveConfig(currentToken);
                InstallTask();
                status.Text = "Computador aprovado. Agente instalado e rodando em segundo plano.";
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message, "Aponti TV", MessageBoxButtons.OK, MessageBoxIcon.Error);
                status.Text = ex.Message;
            }
        }

        string PostPairing()
        {
            using (var client = new WebClient())
            {
                client.Headers[HttpRequestHeader.ContentType] = "application/x-www-form-urlencoded";
                var body = "name=" + Url(computerName.Text.Trim()) +
                    "&hostname=" + Url(Environment.MachineName) +
                    "&agent_version=" + Url(AgentVersion) +
                    "&pairing_secret=" + Url(pairingSecret);
                return client.UploadString(SelectedServerUrl() + "/agent/pairings", body);
            }
        }

        string GetPairingStatus()
        {
            using (var client = new WebClient())
            {
                return client.DownloadString(SelectedServerUrl() + "/agent/pairings/" + pairingId + "?pairing_secret=" + Url(pairingSecret) + "&agent_version=" + Url(AgentVersion));
            }
        }

        void SaveConfig(string token)
        {
            var json = "{\n" +
                "  \"mode\": \"" + (publicMode.Checked ? "public" : "local") + "\",\n" +
                "  \"server_url\": \"" + Escape(SelectedServerUrl()) + "\",\n" +
                "  \"local_url\": \"" + Escape(Normalize(localUrl.Text)) + "\",\n" +
                "  \"public_url\": \"" + Escape(Normalize(publicUrl.Text)) + "\",\n" +
                "  \"name\": \"" + Escape(computerName.Text.Trim()) + "\",\n" +
                "  \"token\": \"" + Escape(token) + "\",\n" +
                "  \"pairing_id\": \"" + Escape(pairingId) + "\",\n" +
                "  \"pairing_secret\": \"" + Escape(pairingSecret) + "\",\n" +
                "  \"interval_seconds\": " + interval.Value + "\n" +
                "}\n";
            File.WriteAllText(ConfigPath, json, Encoding.UTF8);
        }

        void InstallTask()
        {
            StopAgentLoop();
            RemoveLegacyScheduledTask();
            File.WriteAllText(
                StartupScriptPath,
                "Set WshShell = CreateObject(\"WScript.Shell\")\r\n" +
                "WshShell.Run \"powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"\"" + AgentPath + "\"\"\", 0, False\r\n",
                Encoding.ASCII
            );

            RunHidden("powershell.exe", "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"" + AgentPath + "\" -Once");
            StartAgentLoop();
        }

        void TestNow(object sender, EventArgs e)
        {
            try
            {
                if (currentToken.Length == 0) throw new Exception("Aprove o computador antes de testar.");
                Directory.CreateDirectory(InstallDir);
                File.WriteAllText(AgentPath, AgentScript(), Encoding.UTF8);
                SaveConfig(currentToken);
                var result = RunHidden("powershell.exe", "-NoProfile -ExecutionPolicy Bypass -File \"" + AgentPath + "\" -Once");
                status.Text = result.Length > 0 ? result : "Teste executado.";
            }
            catch (Exception ex) { MessageBox.Show(ex.Message, "Aponti TV", MessageBoxButtons.OK, MessageBoxIcon.Error); }
        }

        void StartTask(object sender, EventArgs e)
        {
            try
            {
                if (currentToken.Length > 0 && !TaskExists()) InstallTask();
                StopAgentLoop();
                StartAgentLoop();
                status.Text = "Agente iniciado.";
            }
            catch (Exception ex) { status.Text = "Nao foi possivel iniciar. Salve/conecte novamente. " + ex.Message; }
        }

        void StopTask(object sender, EventArgs e)
        {
            try
            {
                if (!TaskExists())
                {
                    status.Text = "A inicializacao ainda nao foi instalada.";
                    return;
                }
                StopAgentLoop();
                status.Text = "Agente parado.";
            }
            catch (Exception ex) { status.Text = ex.Message; }
        }

        bool TaskExists()
        {
            return File.Exists(StartupScriptPath);
        }

        void StartAgentLoop()
        {
            var process = new Process();
            process.StartInfo.FileName = "wscript.exe";
            process.StartInfo.Arguments = "\"" + StartupScriptPath + "\"";
            process.StartInfo.UseShellExecute = true;
            process.StartInfo.WindowStyle = ProcessWindowStyle.Hidden;
            process.Start();
        }

        void StopAgentLoop()
        {
            RunHidden("powershell.exe", "-NoProfile -ExecutionPolicy Bypass -Command \"$own = $PID; Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'powershell.exe' -and $_.CommandLine -like '*aponti_wallpaper_agent.ps1*' -and $_.ProcessId -ne $own } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }\"");
        }

        void RemoveLegacyScheduledTask()
        {
            try
            {
                RunHidden("powershell.exe", "-NoProfile -ExecutionPolicy Bypass -Command \"Unregister-ScheduledTask -TaskName 'ApontiTV Wallpaper Agent' -Confirm:$false -ErrorAction SilentlyContinue; exit 0\"");
            }
            catch
            {
                // Tarefa antiga nao deve bloquear a instalacao do agente.
            }
        }
        void OpenLogs(object sender, EventArgs e) { Directory.CreateDirectory(InstallDir); if (!File.Exists(LogPath)) File.WriteAllText(LogPath, ""); Process.Start("notepad.exe", LogPath); }

        string SelectedServerUrl() { return Normalize(publicMode.Checked ? publicUrl.Text : localUrl.Text); }
        string Normalize(string value) { var clean = (value ?? "").Trim().TrimEnd('/'); if (clean.Length == 0) return ""; if (!clean.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !clean.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) clean = "http://" + clean; return clean; }
        string Escape(string value) { return (value ?? "").Replace("\\", "\\\\").Replace("\"", "\\\""); }
        string Url(string value) { return Uri.EscapeDataString(value ?? ""); }
        string JsonValue(string json, string key, string fallback = "") { var match = Regex.Match(json, "\"" + Regex.Escape(key) + "\"\\s*:\\s*(?:\"(?<s>(?:\\\\.|[^\"])*)\"|(?<n>\\d+)|(?<b>true|false))"); if (!match.Success) return fallback; var value = match.Groups["s"].Success ? match.Groups["s"].Value : (match.Groups["n"].Success ? match.Groups["n"].Value : match.Groups["b"].Value); return value.Replace("\\\"", "\"").Replace("\\\\", "\\"); }
        decimal NumberValue(string json, string key, decimal fallback) { decimal value; return decimal.TryParse(JsonValue(json, key), out value) ? value : fallback; }
        string RandomHex(int bytes) { var data = new byte[bytes]; using (var rng = RandomNumberGenerator.Create()) rng.GetBytes(data); var sb = new StringBuilder(); foreach (var b in data) sb.Append(b.ToString("x2")); return sb.ToString(); }

        string RunHidden(string file, string args)
        {
            var process = new Process();
            process.StartInfo.FileName = file;
            process.StartInfo.Arguments = args;
            process.StartInfo.UseShellExecute = false;
            process.StartInfo.CreateNoWindow = true;
            process.StartInfo.RedirectStandardOutput = true;
            process.StartInfo.RedirectStandardError = true;
            process.Start();
            var output = process.StandardOutput.ReadToEnd();
            var error = process.StandardError.ReadToEnd();
            process.WaitForExit();
            if (process.ExitCode != 0)
            {
                var detail = (error + "\n" + output).Trim();
                if (detail.Length == 0) detail = "Comando oculto falhou sem mensagem. Comando: " + file + " " + args;
                throw new Exception(detail);
            }
            return output.Trim();
        }

        string AgentScript()
        {
            return @"param([switch]$Once)
$ErrorActionPreference = ""Stop""
$AgentVersion = ""1.3.1""
$ConfigDir = Join-Path $env:ProgramData ""ApontiTV\WallpaperAgent""
$ConfigPath = Join-Path $ConfigDir ""agent.json""
$WallpaperPath = Join-Path $ConfigDir ""wallpaper.jpeg""
$StatePath = Join-Path $ConfigDir ""state.json""
function Read-JsonFile($Path) { if (-not (Test-Path -LiteralPath $Path)) { return $null }; $raw = Get-Content -LiteralPath $Path -Raw; if ([string]::IsNullOrWhiteSpace($raw)) { return $null }; return $raw | ConvertFrom-Json }
function Write-JsonFile($Path, $Value) { $Value | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Path -Encoding UTF8 }
function Get-FileSha256($Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Set-WindowsWallpaper($Path) {
  Add-Type @""
using System;
using System.Runtime.InteropServices;
public class WallpaperApi { [DllImport(""user32.dll"", SetLastError = true)] public static extern bool SystemParametersInfo(int uAction, int uParam, string lpvParam, int fuWinIni); }
""@
  Set-ItemProperty -Path ""HKCU:\Control Panel\Desktop"" -Name WallpaperStyle -Value ""10""
  Set-ItemProperty -Path ""HKCU:\Control Panel\Desktop"" -Name TileWallpaper -Value ""0""
  [void][WallpaperApi]::SystemParametersInfo(20, 0, $Path, 3)
  Set-ItemProperty -Path ""HKCU:\Control Panel\Desktop"" -Name WallpaperStyle -Value ""10""
  Set-ItemProperty -Path ""HKCU:\Control Panel\Desktop"" -Name TileWallpaper -Value ""0""
}
function Confirm-WallpaperApplied($Config, $Headers, $Version) {
  if ([string]::IsNullOrWhiteSpace($Version)) { return }
  for ($attempt = 1; $attempt -le 3; $attempt++) {
    try {
      Invoke-RestMethod -Method Post -Uri ""$($Config.server_url)/agent/wallpaper/applied"" -Headers $Headers -Body @{ version = $Version } -TimeoutSec 15 | Out-Null
      return
    } catch {
      if ($attempt -eq 3) { throw }
      Start-Sleep -Milliseconds 700
    }
  }
}
function Invoke-AgentCycle($Config) {
  $headers = @{ Authorization = ""Bearer $($Config.token)""; ""X-Aponti-Agent-Hostname"" = $env:COMPUTERNAME; ""X-Aponti-Agent-Version"" = $AgentVersion }
  $payload = Invoke-RestMethod -Method Get -Uri ""$($Config.server_url)/agent/wallpaper"" -Headers $headers -TimeoutSec 30
  if (-not $payload.available) { return }
  $state = Read-JsonFile $StatePath
  $currentVersion = if ($state) { $state.version } else { """" }
  if ($currentVersion -eq $payload.version -and (Test-Path -LiteralPath $WallpaperPath)) { Set-WindowsWallpaper $WallpaperPath; Confirm-WallpaperApplied $Config $headers $payload.version; return }
  $downloadPath = ""$WallpaperPath.download""
  Invoke-WebRequest -UseBasicParsing -Uri $payload.url -OutFile $downloadPath -TimeoutSec 60
  $downloadHash = Get-FileSha256 $downloadPath
  if ($payload.sha256 -and $downloadHash -ne $payload.sha256.ToLowerInvariant()) { Remove-Item -LiteralPath $downloadPath -Force -ErrorAction SilentlyContinue; throw ""Hash do wallpaper nao confere."" }
  Move-Item -LiteralPath $downloadPath -Destination $WallpaperPath -Force
  Set-WindowsWallpaper $WallpaperPath
  Write-JsonFile $StatePath ([pscustomobject]@{ version = $payload.version; sha256 = $payload.sha256; applied_at = (Get-Date).ToString(""o"") })
  Confirm-WallpaperApplied $Config $headers $payload.version
}
New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null
$config = Read-JsonFile $ConfigPath
if ($null -eq $config -or [string]::IsNullOrWhiteSpace($config.server_url) -or [string]::IsNullOrWhiteSpace($config.token)) { throw ""Agente sem configuracao."" }
do { try { Invoke-AgentCycle $config } catch { Add-Content -LiteralPath (Join-Path $ConfigDir ""agent.log"") -Value ""$(Get-Date -Format o) $($_.Exception.Message)"" }; if ($Once) { break }; Start-Sleep -Seconds ([Math]::Max(5, [int]$config.interval_seconds)) } while ($true)
";
        }
    }
}
