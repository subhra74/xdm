using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;

namespace XDM.WinForm.UI.FormHelper
{
    internal static class DarkModeHelper
    {
        public static void StyleFlatTextBox(TextBox textBox, IFormColors colors)
        {
            textBox.BackColor = colors.TextBackColor;
            textBox.ForeColor = colors.ToolbarButtonForeColor;
            textBox.BorderStyle = BorderStyle.None;
            textBox.Dock = DockStyle.Fill;
        }

        public static void StyleFlatTextBox(TextBox textBox, Color bg, Color fg)
        {
            textBox.BackColor = bg;
            textBox.ForeColor = fg;
        }

        public static void StyleFlatButton(Button button, IFormColors colors)
        {
            button.FlatStyle = FlatStyle.Flat;
            button.BackColor = colors.ToolbarBackColor;
            button.ForeColor = colors.ToolbarButtonForeColor;
            button.FlatAppearance.BorderSize = 0;
        }

        public static void StyleFlatButton(Button button, Color bg, Color fg)
        {
            button.FlatStyle = FlatStyle.Flat;
            button.BackColor = bg;
            button.ForeColor = fg;
            button.FlatAppearance.BorderSize = 0;
        }

        public static void EnabledDarkMode(ComboBox comboBox)
        {
            comboBox.DrawMode = DrawMode.OwnerDrawFixed;
            comboBox.DrawItem += ComboBox_DrawItem;
            comboBox.FlatStyle = FlatStyle.Flat;
        }

        public static void EnableDarkMode(ComboBox comboBox, Color bg, Color fg)
        {
            comboBox.FlatStyle = FlatStyle.Flat;
            comboBox.BackColor = bg;
            comboBox.ForeColor = fg;
        }

        private static void ComboBox_DrawItem(object sender, DrawItemEventArgs e)
        {
            e.DrawBackground();
            e.DrawFocusRectangle();
            if (sender is ComboBox comboBox)
            {
                TextRenderer.DrawText(e.Graphics, (string)comboBox.Items[e.Index],
                    e.Font, e.Bounds, comboBox.ForeColor, TextFormatFlags.Left | TextFormatFlags.VerticalCenter);
            }
        }

        public static void EnableDarkMode(DataGridView dataGridView)
        {
#if !NET35
            var dgvType = dataGridView.GetType();
            var vs = dgvType.GetProperty("VerticalScrollBar",
                  BindingFlags.Instance | BindingFlags.NonPublic);
            var vScroll = vs.GetValue(dataGridView) as VScrollBar;
            vScroll.HandleCreated += (a, b) =>
            {
                //https://stackoverflow.com/questions/53501268/win10-dark-theme-how-to-use-in-winapi
                SetWindowTheme(vScroll.Handle, "DarkMode_Explorer", null);
            };

            var hs = dgvType.GetProperty("VerticalScrollBar",
                  BindingFlags.Instance | BindingFlags.NonPublic);
            var hScroll = vs.GetValue(dataGridView) as VScrollBar;
            vScroll.HandleCreated += (a, b) =>
            {
                //https://stackoverflow.com/questions/53501268/win10-dark-theme-how-to-use-in-winapi
                SetWindowTheme(hScroll.Handle, "DarkMode_Explorer", null);
            };
#endif
        }

        [DllImport("uxtheme.dll", SetLastError = true, CharSet = CharSet.Unicode, EntryPoint = "#133")]
        public static extern int AllowDarkModeForWindow(IntPtr hWnd, int allow);

        [DllImport("uxtheme.dll", SetLastError = true, ExactSpelling = true, CharSet = CharSet.Unicode)]
        public static extern int SetWindowTheme(IntPtr hWnd, string pszSubAppName, string pszSubIdList);

        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int attrValue, int attrSize);

        private const int DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19;
        private const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;

        public static bool UseImmersiveDarkMode(IntPtr handle, bool enabled)
        {
            if (IsWindows10OrGreater(17763))
            {
                var attribute = DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1;
                if (IsWindows10OrGreater(18985))
                {
                    attribute = DWMWA_USE_IMMERSIVE_DARK_MODE;
                }

                int useImmersiveDarkMode = enabled ? 1 : 0;
                return DwmSetWindowAttribute(handle, (int)attribute, ref useImmersiveDarkMode, sizeof(int)) == 0;
            }

            return false;
        }

        private static bool IsWindows10OrGreater(int build = -1)
        {
            return Environment.OSVersion.Version.Major >= 10 && Environment.OSVersion.Version.Build >= build;
        }


    }
}
