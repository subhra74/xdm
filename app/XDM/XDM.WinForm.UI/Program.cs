using System;
using System.Collections.Generic;
using System.Net;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using TraceLog;
using Translations;
using XDM.Core.Lib.Common;
using XDMApp;

namespace XDM.WinForm.UI
{
    //to support tls1.2 in net3.5 use following
    //https://stackoverflow.com/questions/43240611/net-framework-3-5-and-tls-1-2
    static class Program
    {

        private const string DisableCachingName = @"TestSwitch.LocalAppContext.DisableCaching";
        private const string DontEnableSchUseStrongCryptoName = @"Switch.System.Net.DontEnableSchUseStrongCrypto";
        /// <summary>
        ///  The main entry point for the application.
        /// </summary>
        [STAThread]
        static void Main(string[] args)
        {
            if (Environment.OSVersion.Version.Major >= 6)
            {
                SetProcessDPIAware();
            }
            Application.ThreadException += Application_ThreadException;
            Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
            AppDomain.CurrentDomain.UnhandledException += CurrentDomain_UnhandledException;
            //Log.Debug(Environment.Version.ToString());
            //ThreadPool.GetMinThreads(out int wt, out int cpt);
            //Log.Debug("cpt: " + cpt + " wt: " + wt);
            //ThreadPool.SetMaxThreads(4, 4);
            //ThreadPool.GetMinThreads(out wt, out cpt);
            //Log.Debug("cpt: " + cpt + " wt: " + wt);
            ServicePointManager.ServerCertificateValidationCallback += (a, b, c, d) =>
            {
                return true;
            };
            //foreach (var attr in new object().GetType().Assembly.GetCustomAttributes(typeof(AssemblyFileVersionAttribute), true))
            //{
            //    var version = ((AssemblyFileVersionAttribute)attr).Version;
            //    Console.WriteLine(version);
            //}
            //Console.WriteLine(new object().GetType().Assembly.GetCustomAttributes(typeof(AssemblyFileVersionAttribute),true));
            ServicePointManager.DefaultConnectionLimit = 100;
            //SetTls();

#if NET45
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12 | SecurityProtocolType.Tls11 | SecurityProtocolType.Tls;
#elif !NET35
            ServicePointManager.SecurityProtocol = SecurityProtocolType.SystemDefault;
#endif
#if NET46_OR_GREATER

            AppContext.SetSwitch(DisableCachingName, true);
            AppContext.SetSwitch(DontEnableSchUseStrongCryptoName, true);
#endif
            var commandOptions = ArgsProcessor.ParseArgs(args);
            //Application.SetHighDpiMode(HighDpiMode.SystemAware);
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            var app = new XDMApp.XDMApp();
            app.Args = args;
            TextResource.Load(Config.Instance.Language);
            var appWin = new AppWinPeer();
            app.AppUI = new XDMApp.AppWin(appWin, app);
            appWin.Visible = !commandOptions.ContainsKey("-m");
            //app.LoadDownloadList();
            app.StartClipboardMonitor();
            app.StartScheduler();
            app.StartNativeMessagingHost();
            Application.Run(new ApplicationContext());
        }

        private static void CurrentDomain_UnhandledException(object sender, UnhandledExceptionEventArgs e)
        {
            Log.Debug(string.Format("Unhandled exception caught {0} and will {1}",
                e.ExceptionObject,
                e.IsTerminating ? "Terminating" : "Continue"));
        }

        private static void Application_ThreadException(object sender, System.Threading.ThreadExceptionEventArgs e)
        {
            Log.Debug(string.Format("Unhandled exception caught {0}", e.Exception));
        }

        [DllImport("user32.dll", SetLastError = true)]
        static extern bool SetProcessDPIAware();

        //private static void SetTls()
        //{
        //    //foreach(var s in typeof(ServicePointManager)
        //    //.GetFields(BindingFlags.Static | BindingFlags.NonPublic))
        //    //{
        //    //    Console.WriteLine(s);
        //    //}
        //    //Console.WriteLine(typeof(Application));
        //    //var fields = typeof(Application).GetType().GetFields( BindingFlags.Static | BindingFlags.NonPublic);
        //    //foreach (var item in fields)
        //    //{
        //    //    Console.WriteLine("Name: " + item.Name);
        //    //}
        //    //Console.WriteLine("Name: " + fields.Length);
        //    var field = typeof(ServicePointManager).GetField("s_SecurityProtocolType", BindingFlags.Static | BindingFlags.NonPublic | BindingFlags.Public | BindingFlags.Instance | BindingFlags.FlattenHierarchy);
        //    field.SetValue(null, (SecurityProtocolType)3072);
        //    Console.WriteLine("Name: " + field);
        //}
    }
}
