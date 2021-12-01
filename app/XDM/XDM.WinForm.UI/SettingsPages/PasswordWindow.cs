using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Text;

using System.Windows.Forms;
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
    }
}
