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
            win.Show();
            app.AppUI = new XDMApp.AppWin(win, app);
            Log.Debug("Application_Startup");
        }
    }

    public enum Skin { Light, Dark }
}
