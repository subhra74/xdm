using System;
using System.IO;
using System.Windows.Forms;
using Translations;
using XDM.Core.Lib.Common;

#if !(NET472_OR_GREATER || NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI
{
    public partial class LanguageSelectionDlg : Form
    {
        public LanguageSelectionDlg()
        {
            InitializeComponent();

            Text = TextResource.GetText("MENU_LANG");
            label1.Text = TextResource.GetText("MSG_LANG1");
            label2.Text = TextResource.GetText("MSG_LANG2");
            button1.Text = TextResource.GetText("MSG_OK");
            button2.Text = TextResource.GetText("ND_CANCEL");

            label1.Margin = new Padding(0, 0, 0, LogicalToDeviceUnits(5));
            label2.Margin = new Padding(0, 0, 0, LogicalToDeviceUnits(5));
            comboBox1.Margin = new Padding(0, 0, 0, LogicalToDeviceUnits(5));
            button1.Margin = new Padding(0, 0, LogicalToDeviceUnits(5), 0);
            button2.Margin = new Padding(LogicalToDeviceUnits(5), 0, 0, 0);
            button1.Padding = new Padding(LogicalToDeviceUnits(10), LogicalToDeviceUnits(5), LogicalToDeviceUnits(10), LogicalToDeviceUnits(5));
            button2.Padding = new Padding(LogicalToDeviceUnits(5));

            foreach (var file in Directory.GetFiles(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Lang")))
            {
                comboBox1.Items.Add(Path.GetFileNameWithoutExtension(file));
            }

            comboBox1.SelectedItem = Config.Instance.Language;
        }

        private void button1_Click(object sender, EventArgs e)
        {
            if (comboBox1.SelectedIndex >= 0)
            {
                Config.Instance.Language = comboBox1.SelectedItem.ToString();
                Config.SaveConfig();
                Close();
            }
        }

        private void button2_Click(object sender, EventArgs e)
        {
            Close();
        }
    }
}
