using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Windows.Forms;

namespace XDM.Wpf.UI
{
    internal static class AppTrayIcon
    {
        private static NotifyIcon? notifyIcon;

        public static event EventHandler? TrayClick;

        public static void AttachToSystemTray()
        {
            notifyIcon = new NotifyIcon
            {
                Text = "XDM",
                Visible = true,
                Icon = SystemIcons.Application
            };
            notifyIcon.MouseClick += NotifyIcon_MouseClick;
        }

        private static void NotifyIcon_MouseClick(object sender, MouseEventArgs e)
        {
            TrayClick?.Invoke(sender, e);
        }

        public static void DetachFromSystemTray()
        {
            notifyIcon?.Dispose();
        }
    }
}
