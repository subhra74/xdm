using System;
using System.Net;
using System.Linq;
using System.Windows;
using TraceLog;
using XDM.Core;
using XDM.Core.Util;
using System.Windows.Interop;
using XDM.Core.DataAccess;
using System.IO;

namespace XDM.Wpf.UI
{
    /// <summary>
    /// Interaction logic for App.xaml
    /// </summary>
    public partial class App : Application
    {
        private const string DisableCachingName = @"TestSwitch.LocalAppContext.DisableCaching";
        private const string DontEnableSchUseStrongCryptoName = @"Switch.System.Net.DontEnableSchUseStrongCrypto";

        public static Skin Skin = ShouldSelectDarkTheme() ? Skin.Dark : Skin.Light;
        private XDMApp.AppService app;
        private MainWindow win;

        public App()
        {
            ServicePointManager.ServerCertificateValidationCallback += (a, b, c, d) => true;
            ServicePointManager.DefaultConnectionLimit = 100;

#if NET45
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12 | SecurityProtocolType.Tls11 | SecurityProtocolType.Tls;
#elif !NET35
            ServicePointManager.SecurityProtocol = SecurityProtocolType.SystemDefault;
#endif
#if NET46_OR_GREATER

            AppContext.SetSwitch(DisableCachingName, true);
            AppContext.SetSwitch(DontEnableSchUseStrongCryptoName, true);
#endif
        }

        private void Application_Startup(object sender, StartupEventArgs e)
        {
            //Only if user has chosen to generate log
            var debugMode = Environment.GetEnvironmentVariable("XDM_DEBUG_MODE");
            if (!string.IsNullOrEmpty(debugMode) && debugMode == "1")
            {
                var logFile = System.IO.Path.Combine(Config.DataDir, "log.txt");
                Log.InitFileBasedTrace(System.IO.Path.Combine(Config.DataDir, "log.txt"));
            }
            Log.Debug($"Application_Startup::args->: {string.Join(" ", Environment.GetCommandLineArgs())}");

            AppDomain.CurrentDomain.UnhandledException += CurrentDomain_UnhandledException;

            AppDB.Instance.Init(Path.Combine(Config.DataDir, "downloads.db"));

            app = new XDMApp.AppService();
            win = new MainWindow();

            var args = Environment.GetCommandLineArgs();
            var commandOptions = ArgsProcessor.ParseArgs(args, 1);
            app.Args = args.Skip(1).ToArray();

            AppTrayIcon.AttachToSystemTray();
            AppTrayIcon.TrayClick += (_, _) =>
            {
                win.Show();
                if (win.WindowState == WindowState.Minimized)
                {
                    win.WindowState = WindowState.Normal;
                }
                win.Activate();
            };
            app.AppUI = new XDMApp.AppUIController(win, app);
            app.AppUI.WindowLoaded += (_, _) => app.StartClipboardMonitor();
            app.StartScheduler();
            app.StartNativeMessagingHost();
            if (!commandOptions.ContainsKey("-m"))
            {
                win.Show();
                if (commandOptions.ContainsKey("-i"))
                {
                    Config.Instance.RunOnLogon = true;
                    Config.SaveConfig();
                    win.ShowBrowserMonitoringDialog(app);
                }
            }
        }

        private void Application_Exit(object sender, ExitEventArgs e)
        {
            AppTrayIcon.DetachFromSystemTray();
        }

        private static void CurrentDomain_UnhandledException(object sender, UnhandledExceptionEventArgs e)
        {
            Log.Debug(string.Format("Unhandled exception caught {0} and will {1}",
                   e.ExceptionObject,
                   e.IsTerminating ? "Terminating" : "Continue"));
        }

        private void Application_DispatcherUnhandledException(object sender, System.Windows.Threading.DispatcherUnhandledExceptionEventArgs e)
        {
            Log.Debug(string.Format("Unhandled exception caught {0} and application would have been terminated",
                e.Exception));
            e.Handled = true;
            //AppTrayIcon.DetachFromSystemTray();
            //Environment.Exit(1);
        }

        private static bool ShouldSelectDarkTheme()
        {
            if ((Environment.GetCommandLineArgs()?.Contains("-i") ?? false)
                && Environment.OSVersion.Version.Major >= 10
                && DarkModeHelper.IsWin10DarkThemeActive())
            {
                Config.Instance.AllowSystemDarkTheme = true;
                Config.SaveConfig();
                return true;
            }
            else
            {
                return Config.Instance.AllowSystemDarkTheme ? true : false;
            }
        }

        private void Application_SessionEnding(object sender, SessionEndingCancelEventArgs e)
        {
            Log.Debug("Application_SessionEnding: Session ending message received...");
            //e.Cancel = false;
            Environment.Exit(0);
        }
    }

    public enum Skin { Light, Dark }
}
