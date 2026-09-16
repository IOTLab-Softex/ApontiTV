using System.Diagnostics;
using System.IO;
using System.Text;
using System.Windows;
using System.Windows.Threading;
using Forms = System.Windows.Forms;
using Brushes = System.Windows.Media.Brushes;

namespace ApontiTV.ServerManager;

public partial class MainWindow : Window
{
    private readonly string root;
    private readonly string script;
    private readonly string startupFile;
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(2) };
    private readonly Forms.NotifyIcon trayIcon;
    private readonly bool startInTray;
    private bool busy;
    private bool updatingStatus;
    private bool exiting;

    public MainWindow(bool startInTray = false)
    {
        InitializeComponent();
        this.startInTray = startInTray;
        if (startInTray) { ShowInTaskbar = false; WindowState = WindowState.Minimized; }
        root = FindProjectRoot(AppContext.BaseDirectory);
        script = Path.Combine(root, "bin", "aponti_tv.ps1");
        startupFile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Startup), "ApontiTVStartup.vbs");
        timer.Tick += async (_, _) => await UpdateStatusAsync();
        trayIcon = CreateTrayIcon();
        Loaded += async (_, _) => { await UpdateStatusAsync(); RefreshLogs(); timer.Start(); if (this.startInTray) { Hide(); trayIcon.ShowBalloonTip(2500, "Aponti TV", "Servidor iniciado e executando na bandeja.", Forms.ToolTipIcon.Info); await RunActionAsync("start", "Iniciando serviços"); } };
        Closing += (_, e) => { if (!exiting) { e.Cancel = true; Hide(); trayIcon.ShowBalloonTip(1800, "Aponti TV", "O gerenciador continua ativo na bandeja.", Forms.ToolTipIcon.Info); } };
        Closed += (_, _) => { timer.Stop(); trayIcon.Dispose(); };
    }

    private Forms.NotifyIcon CreateTrayIcon()
    {
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("Abrir painel", null, (_, _) => Dispatcher.Invoke(ShowFromTray));
        menu.Items.Add("Abrir Aponti TV", null, (_, _) => Dispatcher.Invoke(() => Open_Click(this, new RoutedEventArgs())));
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("Iniciar serviços", null, (_, _) => Dispatcher.Invoke(async () => await RunActionAsync("start", "Iniciando serviços")));
        menu.Items.Add("Reiniciar serviços", null, (_, _) => Dispatcher.Invoke(async () => await RunActionAsync("restart", "Reiniciando serviços")));
        menu.Items.Add("Parar serviços", null, (_, _) => Dispatcher.Invoke(async () => await RunActionAsync("stop", "Encerrando serviços")));
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("Sair", null, (_, _) => Dispatcher.Invoke(ExitApplication));
        var executableIcon = System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath ?? string.Empty) ?? System.Drawing.SystemIcons.Application;
        var icon = new Forms.NotifyIcon { Text = "Aponti TV • Servidor", Icon = executableIcon, ContextMenuStrip = menu, Visible = true };
        icon.DoubleClick += (_, _) => Dispatcher.Invoke(ShowFromTray);
        return icon;
    }

    public void ShowFromTray()
    {
        ShowInTaskbar = true;
        Show();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
        Topmost = true; Topmost = false; Focus();
    }

    private void ExitApplication()
    {
        exiting = true;
        trayIcon.Visible = false;
        Close();
        System.Windows.Application.Current.Shutdown();
    }

    private static string FindProjectRoot(string start)
    {
        for (var directory = new DirectoryInfo(start); directory is not null; directory = directory.Parent)
            if (File.Exists(Path.Combine(directory.FullName, "bin", "aponti_tv.ps1"))) return directory.FullName;
        return Path.GetFullPath(Path.Combine(start, "..", "..", "..", "..", ".."));
    }

    private static async Task<Dictionary<int, int>> ListeningPidsAsync()
    {
        using var process = Process.Start(new ProcessStartInfo("netstat.exe", "-ano -p tcp") { UseShellExecute = false, RedirectStandardOutput = true, CreateNoWindow = true });
        if (process is null) return [];
        var output = await process.StandardOutput.ReadToEndAsync();
        await process.WaitForExitAsync();
        var result = new Dictionary<int, int>();
        foreach (var line in output.Split('\n', StringSplitOptions.RemoveEmptyEntries))
        {
            var parts = line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length < 5 || !parts[3].Equals("LISTENING", StringComparison.OrdinalIgnoreCase)) continue;
            var separator = parts[1].LastIndexOf(':');
            if (separator >= 0 && int.TryParse(parts[1][(separator + 1)..], out var port) && int.TryParse(parts[4], out var pid)) result.TryAdd(port, pid);
        }
        return result;
    }

    private async Task UpdateStatusAsync()
    {
        if (updatingStatus) return;
        try
        {
            updatingStatus = true;
            var ports = await ListeningPidsAsync();
            SetService(RailsDot, RailsStatus, RailsDetail, ports.GetValueOrDefault(3000), 3000);
            SetService(NginxDot, NginxStatus, NginxDetail, ports.GetValueOrDefault(80), 80);
            var automatic = File.Exists(startupFile);
            AutoDot.Fill = automatic ? Brushes.MediumSeaGreen : Brushes.SlateGray;
            AutoStatus.Text = automatic ? "Ativada" : "Desativada";
            AutoButton.Content = automatic ? "Desativar inicialização" : "Ativar inicialização";
            ClockText.Text = DateTime.Now.ToString("dd/MM/yyyy  HH:mm:ss");
        }
        finally { updatingStatus = false; }
    }

    private static void SetService(System.Windows.Shapes.Ellipse dot, System.Windows.Controls.TextBlock status, System.Windows.Controls.TextBlock detail, int pid, int port)
    {
        dot.Fill = pid > 0 ? Brushes.MediumSeaGreen : Brushes.SlateGray;
        status.Text = pid > 0 ? "Em execução" : "Parado";
        detail.Text = pid > 0 ? $"Porta {port} • PID {pid}" : $"Porta {port} indisponível";
    }

    private async Task RunActionAsync(string action, string label)
    {
        if (busy) return;
        try
        {
            busy = true; SetButtons(false); MessageText.Text = $"{label}..."; Cursor = System.Windows.Input.Cursors.Wait;
            if (!File.Exists(script)) throw new FileNotFoundException("Script de controle não encontrado.", script);
            using var process = Process.Start(new ProcessStartInfo("powershell.exe", $"-NoProfile -ExecutionPolicy Bypass -File \"{script}\" {action}") { WorkingDirectory = root, UseShellExecute = false, RedirectStandardError = true, CreateNoWindow = true }) ?? throw new InvalidOperationException("Não foi possível iniciar o comando.");
            var error = await process.StandardError.ReadToEndAsync(); await process.WaitForExitAsync();
            if (process.ExitCode != 0) throw new InvalidOperationException(string.IsNullOrWhiteSpace(error) ? "O comando falhou." : error.Trim());
            MessageText.Text = $"{label} concluído.";
        }
        catch (Exception ex) { MessageText.Text = $"Erro: {ex.Message}"; System.Windows.MessageBox.Show(ex.Message, "Aponti TV", MessageBoxButton.OK, MessageBoxImage.Error); }
        finally { busy = false; SetButtons(true); Cursor = System.Windows.Input.Cursors.Arrow; await UpdateStatusAsync(); RefreshLogs(); }
    }

    private void SetButtons(bool enabled) { StartButton.IsEnabled = enabled; StopButton.IsEnabled = enabled; RestartButton.IsEnabled = enabled; AutoButton.IsEnabled = enabled; }
    private void RefreshLogs()
    {
        var text = new StringBuilder();
        foreach (var name in new[] { "rails_startup.err.log", "rails_startup.out.log", "windows_startup.log", "assets_precompile.log" })
        {
            var path = Path.Combine(root, "log", name); if (!File.Exists(path)) continue;
            text.AppendLine($"-- {name} --"); foreach (var line in ReadLastLines(path, 45)) text.AppendLine(line);
        }
        LogBox.Text = text.ToString(); LogBox.ScrollToEnd();
    }

    private static IEnumerable<string> ReadLastLines(string path, int count)
    {
        try
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            using var reader = new StreamReader(stream);
            var lines = new Queue<string>(count);
            while (reader.ReadLine() is { } line) { if (lines.Count == count) lines.Dequeue(); lines.Enqueue(line); }
            return lines.ToArray();
        }
        catch (IOException) { return ["Log temporariamente indisponível."]; }
        catch (UnauthorizedAccessException) { return ["Sem permissão para ler este log."]; }
    }

    private async void Start_Click(object sender, RoutedEventArgs e) => await RunActionAsync("start", "Iniciando serviços");
    private async void Stop_Click(object sender, RoutedEventArgs e) => await RunActionAsync("stop", "Encerrando serviços");
    private async void Restart_Click(object sender, RoutedEventArgs e) => await RunActionAsync("restart", "Reiniciando serviços");
    private async void Assets_Click(object sender, RoutedEventArgs e) => await RunActionAsync("precompile", "Precompilando assets");
    private async void Auto_Click(object sender, RoutedEventArgs e) => await RunActionAsync(File.Exists(startupFile) ? "uninstall" : "install", "Alterando inicialização automática");
    private void Open_Click(object sender, RoutedEventArgs e) => Process.Start(new ProcessStartInfo("http://localhost/") { UseShellExecute = true });
    private void RefreshLog_Click(object sender, RoutedEventArgs e) => RefreshLogs();
}
