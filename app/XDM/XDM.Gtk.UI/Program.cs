using System;
using System.Net;
using Gtk;
using TraceLog;
using Translations;
using XDM.Core;
using XDM.Core.DataAccess;
using XDMApp = XDM.Core.Application;
using System.Linq;
using XDM.Core.BrowserMonitoring;

namespace XDM.GtkUI
{
    class Program
    {
        private const string DisableCachingName = @"TestSwitch.LocalAppContext.DisableCaching";
        private const string DontEnableSchUseStrongCryptoName = @"Switch.System.Net.DontEnableSchUseStrongCrypto";

        static void Main(string[] args)
        {
            Config.LoadConfig();
            var debugMode = Environment.GetEnvironmentVariable("XDM_DEBUG_MODE");
            if (!string.IsNullOrEmpty(debugMode) && debugMode == "1")
            {
                var logFile = System.IO.Path.Combine(Config.AppDir, "log.txt");
                Log.InitFileBasedTrace(System.IO.Path.Combine(Config.AppDir, "log.txt"));
            }
            Log.Debug("Application_Startup");
            Environment.SetEnvironmentVariable("GTK_USE_PORTAL", "1");
            Gtk.Application.Init("xdm-app", ref args);
            GLib.ExceptionManager.UnhandledException += ExceptionManager_UnhandledException;
            var globalStyleSheet = @"
                                    .large-font{ font-size: 16px; }
                                    .medium-font{ font-size: 14px; }
                                    ";

            var screen = Gdk.Screen.Default;
            var provider = new CssProvider();
            provider.LoadFromData(globalStyleSheet);
            Gtk.StyleContext.AddProviderForScreen(screen, provider, 800);
            //var screen = Gdk.Screen.Default;
            //var provider = new CssProvider();
            //provider.LoadFromData(@".dark 
            //                                    {
            //                                        color: gray;
            //                                        background: rgb(36,41,46);
            //                                    }

            //                                    treeview.view :selected 
            //                                    {
            //                                        background-color: rgb(10,106,182);
            //                                        color: white;
            //                                    }
            //.listt
            //{
            //font-family: Segoe UI;
            //}
            //                                    .dark2
            //                                    {
            //                                        color: gray;
            //                                        background: rgb(35,35,35);
            //                                        /*background: rgb(36,41,46);*/
            //                                    }
            //                                    .toolbar-border-dark
            //                                    {  
            //                                        border-bottom: 1px solid rgb(20,20,20);
            //                                    }
            //                                    .toolbar-border-light
            //                                    {  
            //                                        border-bottom: 2px solid rgb(240,240,240);
            //                                    }
            //                                  ");
            //Gtk.StyleContext.AddProviderForScreen(screen, provider, 800);

            ServicePointManager.ServerCertificateValidationCallback += (a, b, c, d) => true;
            ServicePointManager.DefaultConnectionLimit = 100;

            ServicePointManager.SecurityProtocol = SecurityProtocolType.SystemDefault;

            AppContext.SetSwitch(DisableCachingName, true);
            AppContext.SetSwitch(DontEnableSchUseStrongCryptoName, true);

            Log.Debug("Loading languages...");

            LoadLanguageTexts();

            if (Config.Instance.AllowSystemDarkTheme)
            {
                Gtk.Settings.Default.ThemeName = "Adwaita";
                Gtk.Settings.Default.ApplicationPreferDarkTheme = true;
            }

            var core = new ApplicationCore();
            var app = new XDMApp();
            var win = new MainWindow();

            Log.Debug("Configuring app context...");

            ApplicationContext.Configurer()
                .RegisterApplicationWindow(win)
                .RegisterApplication(app)
                .RegisterApplicationCore(core)
                .RegisterCapturedVideoTracker(new VideoTracker())
                .RegisterClipboardMonitor(new ClipboardMonitor())
                .RegisterLinkRefresher(new LinkRefresher())
                .RegisterPlatformUIService(new GtkPlatformUIService())
                .Configure();

            Log.Debug("Processing arguments...");

            ArgsProcessor.Process(args);

            Log.Debug("Gtk Run...");

            Gtk.Application.Run();
        }

        private static void ExceptionManager_UnhandledException(GLib.UnhandledExceptionArgs args)
        {
            Log.Debug("GLib ExceptionManager_UnhandledException: " + args.ExceptionObject);
            args.ExitApplication = false;
        }

        private static void LoadLanguageTexts()
        {
            Log.Debug("Language loading ...");
            try
            {
                var indexFile = System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, @"Lang\index.txt");
                if (System.IO.File.Exists(indexFile))
                {
                    var lines = System.IO.File.ReadAllLines(indexFile);
                    foreach (var line in lines)
                    {
                        var index = line.IndexOf("=");
                        if (index > 0)
                        {
                            var name = line.Substring(0, index);
                            var value = line.Substring(index + 1);
                            if (name == Config.Instance.Language)
                            {
                                TextResource.Load(value);
                                break;
                            }
                        }
                    }
                }
                Log.Debug("Language loaded.");
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
        }
    }
}
