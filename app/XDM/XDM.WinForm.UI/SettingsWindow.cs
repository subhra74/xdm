using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Drawing.Text;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;

using System.Windows.Forms;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Util;
using XDM.WinForm.UI.SettingsPages;

#if !(NET472_OR_GREATER||NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI
{
    public partial class SettingsWindow : Form
    {
        private IApp app;
        private int initialPage;
        private PrivateFontCollection fontCollection;

        public SettingsWindow(PrivateFontCollection fontCollection, IApp app,
            int initialPage = 0)
        {
            InitializeComponent();
            this.app = app;
            this.initialPage = initialPage;
            this.fontCollection = fontCollection;
            var pagePadding = new Padding(LogicalToDeviceUnits(10));
            var panels = new UserControl[]
            {
                new BrowserMonitoringPage(fontCollection,app),
                new GeneralSettingsPage(fontCollection),
                new NetworkSettingsPage(),
                new PasswordManagerPage(),
                new AdvancedSettingsPage(fontCollection),
            };

            foreach (var panel in panels)
            {
                panel.Dock = DockStyle.Fill;
                panel2.Controls.Add(panel);

                if (panel is AdvancedSettingsPage || panel is PasswordManagerPage)
                {
                    panel.Padding = new Padding(LogicalToDeviceUnits(20));
                }
                else
                {
                    panel.Padding = pagePadding;
                }
            }

            var panelNames = new string[] {
                "Browser monitoring",
                "General settings",
                "Network settings",
                "Password manager",
                "Advanced settings" };

            foreach (var text in panelNames)
            {
                dataGridView1.Rows.Add(text);
            }

            dataGridView1.SelectionChanged += (a, b) =>
            {
                if (dataGridView1.SelectedRows.Count < 1)
                {
                    return;
                }
                var index = dataGridView1.SelectedRows[0];
                panels[index.Index].BringToFront();

                label1.Text = panelNames[index.Index];
            };

            foreach (var panel in panel2.Controls)
            {
                if (panel is ISettingsPage pg)
                {
                    pg.PopulateUI();
                }
            }

            dataGridView1.Rows[2].Selected = true;

            button1.Padding = button2.Padding = new Padding(
                LogicalToDeviceUnits(10),
                LogicalToDeviceUnits(2),
                LogicalToDeviceUnits(10),
                LogicalToDeviceUnits(2));

            dataGridView1.DefaultCellStyle.Padding = new Padding(
                LogicalToDeviceUnits(10),
                LogicalToDeviceUnits(10),
                LogicalToDeviceUnits(0),
                LogicalToDeviceUnits(10));
        }

        private void button2_Click(object sender, EventArgs e)
        {
            foreach (var panel in panel2.Controls)
            {
                if (panel is ISettingsPage pg)
                {
                    pg.UpdateConfig();
                }
            }
            Config.SaveConfig();
            app?.ApplyConfig();
            Dispose();
            Helpers.RunGC();
        }

        private void Win32SettingsWindow_Load(object sender, EventArgs e)
        {
            dataGridView1.Rows[initialPage].Selected = true;
        }

        private void button1_Click(object sender, EventArgs e)
        {
            Dispose();
            Helpers.RunGC();
        }
    }
}
