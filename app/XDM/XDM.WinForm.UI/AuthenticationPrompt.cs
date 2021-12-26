using System;

using System.Windows.Forms;
using XDM.Core.Lib.Common;

namespace XDM.WinForm.UI
{
    public partial class AuthenticationPrompt : Form
    {
        public AuthenticationPrompt()
        {
            InitializeComponent();
        }

        public AuthenticationInfo? Credentials => new AuthenticationInfo { UserName = textBox2.Text, Password = textBox3.Text };

        private void button2_Click(object sender, EventArgs e)
        {
            if (string.IsNullOrEmpty(textBox2.Text))
            {
                MessageBox.Show("User name is empty");
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

        public string PromptText { set => label1.Text = value; }
    }
}
