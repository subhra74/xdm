using System;

using System.Windows.Forms;
using Translations;
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

        public SettingsWindow(IApp app,
            int initialPage = 0)
        {
            InitializeComponent();
            Text = TextResource.GetText("TITLE_SETTINGS");
            button2.Text = TextResource.GetText("DESC_SAVE_Q");
            button1.Text = TextResource.GetText("ND_CANCEL");
            this.app = app;
            this.initialPage = initialPage;
            var pagePadding = new Padding(LogicalToDeviceUnits(10));
            var panels = new UserControl[]
            {
                new BrowserMonitoringPage(app),
                new GeneralSettingsPage(),
                new NetworkSettingsPage(),
                new PasswordManagerPage(),
                new AdvancedSettingsPage(),
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

            var panelNames = new string[]
            {
                TextResource.GetText("SETTINGS_MONITORING"),
                TextResource.GetText("SETTINGS_GENERAL"),
                TextResource.GetText("SETTINGS_NETWORK"),
                TextResource.GetText("SETTINGS_CRED"),
                TextResource.GetText("SETTINGS_ADV")
            };

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
