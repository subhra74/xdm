using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using XDM.Core;

namespace XDM.Wpf.UI
{
    internal static class MsixHelper
    {
        public static readonly bool IsAppContainer = IsMsixPackage();

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern int GetCurrentPackageFullName(ref int packageFullNameLength, StringBuilder packageFullName);

        const long APPMODEL_ERROR_NO_PACKAGE = 15700L;

        private static bool IsMsixPackage()
        {
            try
            {
                int length = 0;
                StringBuilder sb = new StringBuilder(0);
                int result = GetCurrentPackageFullName(ref length, sb);

                sb = new StringBuilder(length);
                result = GetCurrentPackageFullName(ref length, sb);

                return result != APPMODEL_ERROR_NO_PACKAGE;
            }
            catch
            {
                return false;
            }
        }

        //public static void FirstRunAppInit()
        //{
        //    var frFile = Path.Combine(Config.AppDir, AppInfo.APP_VERSION + ".firstrunfile");
        //    if (!File.Exists(frFile))
        //    {
        //        File.WriteAllText(frFile, "");
        //        CopyExtension();
        //        Config.Instance.RunOnLogon = true;
        //        Config.SaveConfig();
        //        ApplicationContext.Application.RunOnUiThread(() =>
        //        {
        //            ApplicationContext.MainWindow.ShowAndActivate();
        //            if (!File.Exists(Path.Combine(Config.AppDir, "browser-integration-attempted")))
        //            {
        //                ApplicationContext.PlatformUIService.ShowBrowserMonitoringDialog();
        //            }
        //        });
        //    }
        //}

        public static void CopyExtension()
        {
            //CopyFilesRecursively(AppDomain.CurrentDomain.BaseDirectory,
            //            Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "chrome-extension");
            CopyFilesRecursively(AppDomain.CurrentDomain.BaseDirectory,
                        Config.AppDir, "chrome-extension");
        }

        private static void CopyFilesRecursively(string sourcePath, string targetPath, string basePath)
        {
            var srcPath = Path.Combine(sourcePath, basePath);
            var dstPath = Path.Combine(targetPath, basePath);
            Directory.CreateDirectory(dstPath);
            foreach (string newPath in Directory.GetFiles(srcPath, "*.*", SearchOption.TopDirectoryOnly))
            {
                var name = Path.GetFileName(newPath);
                File.Copy(newPath, Path.Combine(dstPath, name), true);
            }
            foreach (string newPath in Directory.GetDirectories(srcPath, "*", SearchOption.TopDirectoryOnly))
            {
                var name = Path.GetFileName(newPath);
                CopyFilesRecursively(sourcePath, targetPath, Path.Combine(basePath, name));
            }
        }
    }
}
