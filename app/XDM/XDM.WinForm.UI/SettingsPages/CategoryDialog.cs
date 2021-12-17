using System;
using System.Collections.Generic;
using System.Data;
using System.Linq;
using System.Windows.Forms;
using Translations;
using XDM.WinForm.UI.FormHelper;

#if !(NET472_OR_GREATER||NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI.SettingsPages
{
    public partial class CategoryDialog : Form
    {
        public CategoryDialog()
        {
            InitializeComponent();
            Width = LogicalToDeviceUnits(400);
            Height = LogicalToDeviceUnits(250);
            Padding = new Padding(LogicalToDeviceUnits(10));
            LoadTexts();
        }

        public string CategoryName
        {
            get => textBox1.Text;
            set => textBox1.Text = value;
        }

        public string DefaultDownloadFolder
        {
            get => textBox3.Text;
            set => textBox3.Text = value;
        }

        public IEnumerable<string> FileTypes
        {
            get => textBox2.Text.Replace("\r\n", "").Split(',').Select(x => x.Trim()).Where(x => x.Length > 0);
            set => textBox2.Text = string.Join(",", value.ToArray());
        }

        private void button2_Click(object sender, EventArgs e)
        {
            if (string.IsNullOrEmpty(textBox1.Text.Trim()))
            {
                MessageBox.Show("Missing name");
                return;
            }
            if (string.IsNullOrEmpty(textBox2.Text.Trim()))
            {
                MessageBox.Show("Missing name");
                return;
            }
            DialogResult = DialogResult.OK;
            Visible = false;
        }

        private void button1_Click(object sender, EventArgs e)
        {
            DialogResult = DialogResult.Cancel;
            Visible = false;
        }

        private void button3_Click(object sender, EventArgs e)
        {
            using var fb = new FolderBrowserDialog();
            if (fb.ShowDialog(this) == DialogResult.OK)
            {
                textBox3.Text = fb.SelectedPath;
            }
        }

        private void LoadTexts()
        {
            label1.Text = TextResource.GetText("SORT_NAME");
            label2.Text = TextResource.GetText("SETTINGS_CAT_TYPES");
            label3.Text = TextResource.GetText("SETTINGS_CAT_FOLDER");
            button2.Text = TextResource.GetText("MSG_OK");
            button1.Text = TextResource.GetText("ND_CANCEL");
        }
    }
}
