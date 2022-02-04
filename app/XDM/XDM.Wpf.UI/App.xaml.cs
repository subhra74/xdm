using System;
using System.Net;
using System.Linq;
using System.Windows;
using TraceLog;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Util;

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
        private XDMApp.XDMApp app;
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
            Log.Debug("Application_Startup");

            AppDomain.CurrentDomain.UnhandledException += CurrentDomain_UnhandledException;

            app = new XDMApp.XDMApp();
            win = new MainWindow();

            var args = Environment.GetCommandLineArgs();
            var commandOptions = ArgsProcessor.ParseArgs(args, 1);
            app.Args = args;

            AppTrayIcon.AttachToSystemTray();
            AppTrayIcon.TrayClick += (_, _) =>
            {
                win.Show();
            };
            app.AppUI = new XDMApp.AppWin(win, app);
            app.StartClipboardMonitor();
            app.StartScheduler();
            app.StartNativeMessagingHost();
            if (!commandOptions.ContainsKey("-m"))
            {
                win.Show();
                if (commandOptions.ContainsKey("-i"))
                {
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
            Log.Debug(string.Format("Unhandled exception caught {0} and application will terminate",
                e.Exception));
            AppTrayIcon.DetachFromSystemTray();
            Environment.Exit(1);
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
    }

    public enum Skin { Light, Dark }
}
