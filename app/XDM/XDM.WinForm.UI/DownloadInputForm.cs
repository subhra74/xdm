using System;
using System.Windows.Forms;
using XDM.Core.Lib.Util;

namespace XDM.WinForm.UI
{
    public partial class DownloadInputForm : Form
    {
        public string Url { get; private set; }
        public DownloadInputForm()
        {
            InitializeComponent();
        }

        private void button1_Click(object sender, EventArgs e)
        {
            if (!Helpers.IsUriValid(textBox1.Text))
            {
                MessageBox.Show("Please enter valid address");
                return;
            }
            this.Url = textBox1.Text;
            DialogResult = DialogResult.OK;
            Dispose();
        }

        private void button2_Click(object sender, EventArgs e)
        {
            this.Url = string.Empty;
            DialogResult = DialogResult.Cancel;
            Dispose();
        }

        private void DownloadInputForm_Load(object sender, EventArgs e)
        {
            label1.Text = "Address";
            button1.Text = "Download";
            button2.Text = "Cancel";
        }
    }
}
