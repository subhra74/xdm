using Microsoft.Win32;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace XDM.WinForm.UI.FormHelper
{
    internal static class ImmersiveThemeHelper
    {
        public static bool IsDarkThemeActive()
        {
            //return false;
            try
            {
                using var rk = Registry.CurrentUser.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Themes\Personalize");
                if (rk == null) return false;
                var appsUseLightTheme = (Int32)rk.GetValue("AppsUseLightTheme");
                return appsUseLightTheme == 0;
            }
            catch { }
            return false;
        }
    }
}
