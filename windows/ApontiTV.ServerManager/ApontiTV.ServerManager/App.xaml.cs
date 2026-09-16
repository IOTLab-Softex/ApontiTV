using System.Threading;
using System.Windows;

namespace ApontiTV.ServerManager;

public partial class App : System.Windows.Application
{
    private Mutex? instanceMutex;
    private EventWaitHandle? showWindowEvent;
    private RegisteredWaitHandle? showWindowRegistration;

    protected override void OnStartup(StartupEventArgs e)
    {
        const string mutexName = "Local\\ApontiTV.ServerManager.SingleInstance";
        const string eventName = "Local\\ApontiTV.ServerManager.ShowWindow";
        instanceMutex = new Mutex(true, mutexName, out var firstInstance);
        if (!firstInstance)
        {
            try { EventWaitHandle.OpenExisting(eventName).Set(); } catch { }
            instanceMutex.Dispose();
            instanceMutex = null;
            Shutdown();
            return;
        }

        base.OnStartup(e);
        var splash = new SplashWindow();
        splash.Show();
        showWindowEvent = new EventWaitHandle(false, EventResetMode.AutoReset, eventName);
        var trayMode = e.Args.Any(arg => arg.Equals("--tray", StringComparison.OrdinalIgnoreCase));
        var window = new MainWindow(trayMode);
        MainWindow = window;
        showWindowRegistration = ThreadPool.RegisterWaitForSingleObject(showWindowEvent, (_, _) => Dispatcher.BeginInvoke(window.ShowFromTray), null, Timeout.Infinite, false);
        window.Show();
        var splashTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromMilliseconds(1400) };
        splashTimer.Tick += (_, _) => { splashTimer.Stop(); splash.Close(); };
        splashTimer.Start();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        showWindowRegistration?.Unregister(null);
        showWindowEvent?.Dispose();
        if (instanceMutex is not null) { instanceMutex.ReleaseMutex(); instanceMutex.Dispose(); }
        base.OnExit(e);
    }
}
