using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Windows.Forms;

namespace XDM.WinForm.UI
{
    internal static class MenuHelper
    {
        internal static void CustomizeMenuAppearance(ContextMenuStrip menu)
        {
            if (!AppWinPeer.AppsUseLightTheme)
            {
                var backColor = Color.FromArgb(29, 33, 37);
                menu.Renderer = new ToolStripProfessionalRenderer(new DarkMenuColorTable(backColor));
                if (!AppWinPeer.AppsUseLightTheme)
                {
                    menu.BackColor = backColor;
                    menu.ForeColor = Color.WhiteSmoke;
                }
            }
        }

        internal static void FixHiDpiMargin(ContextMenuStrip menu)
        {
            menu.ShowImageMargin = true;
            foreach (var item in menu.Items)
            {
                if (item is ToolStripMenuItem menuItem)
                {
                    menuItem.DisplayStyle = ToolStripItemDisplayStyle.ImageAndText;
                    menuItem.Image = AppWinPeer.MenuMargin;
                }
            }
        }
    }
}
