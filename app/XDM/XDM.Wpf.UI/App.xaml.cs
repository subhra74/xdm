using System;
using System.Windows;
using TraceLog;

namespace XDM.Wpf.UI
{
    /// <summary>
    /// Interaction logic for App.xaml
    /// </summary>
    public partial class App : Application
    {
        public static Skin Skin = Skin.Light;
        private XDMApp.XDMApp app;
        private MainWindow win;

        private void Application_Startup(object sender, StartupEventArgs e)
        {
            app = new XDMApp.XDMApp();
            win = new MainWindow();
            AppTrayIcon.AttachToSystemTray();
            AppTrayIcon.TrayClick += (_, _) =>
            {
                win.Show();
            };
            app.AppUI = new XDMApp.AppWin(win, app);
            app.StartClipboardMonitor();
            app.StartScheduler();
            app.StartNativeMessagingHost();
            //appWin.Visible = !commandOptions.ContainsKey("-m");
            win.Show();
            Log.Debug("Application_Startup");
        }

        private void Application_Exit(object sender, ExitEventArgs e)
        {
            AppTrayIcon.DetachFromSystemTray();
        }
    }

    public enum Skin { Light, Dark }
}
