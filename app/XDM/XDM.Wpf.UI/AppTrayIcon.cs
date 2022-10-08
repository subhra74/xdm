using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Text;
using System.Windows.Forms;
using TraceLog;
using Translations;
using XDM.Core;

namespace XDM.Wpf.UI
{
    internal static class AppTrayIcon
    {
        private static NotifyIcon? notifyIcon;

        public static event EventHandler? TrayClick;

        public static void ShowNotification()
        {
            try
            {
                if (notifyIcon != null && Config.Instance.ShowNotification)
                {
                    notifyIcon.ShowBalloonTip(10000, TextResource.GetText("MSG_DOWNLOAD_VIDEO"),
                        TextResource.GetText("MSG_DWN_VID_DESC"), ToolTipIcon.Info);
                    notifyIcon.BalloonTipClicked += NotifyIcon_BalloonTipClicked;
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
        }

        private static void NotifyIcon_BalloonTipClicked(object sender, EventArgs e)
        {
            XDM.Core.ApplicationContext.PlatformUIService.CreateAndShowMediaGrabber();
        }

        public static void AttachToSystemTray()
        {

            var ctx = new ContextMenu();

            var menuExit = new MenuItem
            {
                Text = TextResource.GetText("MENU_EXIT")
            };
            menuExit.Click += (_, _) => Environment.Exit(0);

            var menuRestore = new MenuItem
            {
                Text = TextResource.GetText("MSG_RESTORE")
            };
            menuRestore.Click += (sender, e) => TrayClick?.Invoke(sender, e);

            ctx.MenuItems.Add(menuRestore);
            ctx.MenuItems.Add(menuExit);

            notifyIcon = new NotifyIcon
            {
                Text = "XDM",
                Visible = true,
                Icon = new Icon(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "xdm-logo.ico")),
                ContextMenu = ctx
            };
            notifyIcon.MouseClick += NotifyIcon_MouseClick;

        }

        private static void NotifyIcon_MouseClick(object sender, MouseEventArgs e)
        {
            if (e.Button == MouseButtons.Left)
            {
                TrayClick?.Invoke(sender, e);
            }
        }

        public static void DetachFromSystemTray()
        {
            notifyIcon?.Dispose();
            notifyIcon = null;
        }
    }
}
