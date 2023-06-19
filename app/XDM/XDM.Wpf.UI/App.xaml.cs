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
using XDMApp = XDM.Core.Application;
using XDM.Core.BrowserMonitoring;
using System.Diagnostics;

namespace XDM.Wpf.UI
{
    /// <summary>
    /// Interaction logic for App.xaml
    /// </summary>
    public partial class App : System.Windows.Application
    {
        private const string DisableCachingName = @"TestSwitch.LocalAppContext.DisableCaching";
        private const string DontEnableSchUseStrongCryptoName = @"Switch.System.Net.DontEnableSchUseStrongCrypto";

        public static Skin Skin = ShouldSelectDarkTheme() ? Skin.Dark : Skin.Light;
        private ApplicationCore core;
        private XDMApp app;
        private MainWindow win;

        public App()
        {
            ServicePointManager.ServerCertificateValidationCallback += (a, b, c, d) => true;
            ServicePointManager.DefaultConnectionLimit = 100;

#if NET45_OR_GREATER
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12 | SecurityProtocolType.Tls11 | SecurityProtocolType.Tls;
            //#elif !NET35
            //            ServicePointManager.SecurityProtocol = SecurityProtocolType.SystemDefault;
#endif
#if NET46_OR_GREATER

            AppContext.SetSwitch(DisableCachingName, true);
            AppContext.SetSwitch(DontEnableSchUseStrongCryptoName, true);
#endif
        }

        private void Application_Startup(object sender, StartupEventArgs e)
        {
            Trace.WriteLine("XDM app start");
            //Only if user has chosen to generate log
            var debugMode = Environment.GetEnvironmentVariable("XDM_DEBUG_MODE");
            if (!string.IsNullOrEmpty(debugMode) && debugMode == "1")
            {
                var logFile = Path.Combine(Config.AppDir, "log.txt");
                Log.InitFileBasedTrace(Path.Combine(Config.AppDir, "log.txt"));
            }
            Log.Debug($"Application_Startup::args->: {string.Join(" ", Environment.GetCommandLineArgs())}");

            AppDomain.CurrentDomain.UnhandledException += CurrentDomain_UnhandledException;

            core = new ApplicationCore();
            win = new MainWindow();
            app = new XDMApp();

            ApplicationContext.FirstRunCallback += ApplicationContext_FirstRunCallback;
            ApplicationContext.Configurer()
                .RegisterApplicationWindow(win)
                .RegisterApplication(app)
                .RegisterApplicationCore(core)
                .RegisterCapturedVideoTracker(new VideoTracker())
                .RegisterClipboardMonitor(new ClipboardMonitor())
                .RegisterLinkRefresher(new LinkRefresher())
                .RegisterPlatformUIService(new WpfPlatformUIService())
                .Configure();

            ArgsProcessor.Process(Environment.GetCommandLineArgs().Skip(1));

            AppTrayIcon.AttachToSystemTray();
            AppTrayIcon.TrayClick += (_, _) =>
            {
                win.ShowAndActivate();
            };
        }

        private void ApplicationContext_FirstRunCallback(object sender, EventArgs e)
        {
            MsixHelper.CopyExtension();
            if (!MsixHelper.IsAppContainer)
            {
                Log.Debug("Not running inside app container");
                Config.Instance.RunOnLogon = true;
            }
            ApplicationContext.Application.RunOnUiThread(() =>
            {
                ApplicationContext.MainWindow.ShowAndActivate();
                ApplicationContext.PlatformUIService.ShowBrowserMonitoringDialog();
            });
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
