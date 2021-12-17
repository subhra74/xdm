using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Text;

using System.Windows.Forms;
using Translations;
using XDM.Core.Lib.Common;

#if !(NET472_OR_GREATER||NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI.SettingsPages
{
    public partial class PasswordWindow : Form
    {
        public PasswordWindow()
        {
            InitializeComponent();
            Width = LogicalToDeviceUnits(350);
            Height = LogicalToDeviceUnits(200);
            LoadTexts();
        }

        public PasswordEntry PasswordEntry
        {
            get
            {
                return new PasswordEntry
                {
                    Host = textBox1.Text,
                    User = textBox2.Text,
                    Password = textBox3.Text
                };
            }
            set
            {
                textBox1.Text = value.Host;
                textBox2.Text = value.User;
                textBox3.Text = value.Password;
            }
        }

        private void button2_Click(object sender, EventArgs e)
        {
            if (string.IsNullOrEmpty(textBox1.Text) || string.IsNullOrEmpty(textBox2.Text))
            {
                MessageBox.Show("Invalid input");
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

        private void LoadTexts()
        {
            label1.Text = TextResource.GetText("DESC_HOST");
            label2.Text = TextResource.GetText("DESC_USER");
            label3.Text = TextResource.GetText("DESC_PASS");
            button2.Text = TextResource.GetText("MSG_OK");
            button1.Text = TextResource.GetText("ND_CANCEL");

        }
    }
}
