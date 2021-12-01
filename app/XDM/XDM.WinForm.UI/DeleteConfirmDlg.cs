using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Windows.Forms;

#if !(NET472_OR_GREATER || NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI
{
    public partial class DeleteConfirmDlg : Form
    {
        public DeleteConfirmDlg()
        {
            InitializeComponent();
            DialogResult = DialogResult.Cancel;
            tableLayoutPanel1.Padding =
                label1.Padding =
                checkBox1.Padding =
                button1.Margin =
                button2.Margin = new Padding(LogicalToDeviceUnits(5));
        }

        public string DescriptionText
        {
            set
            {
                label1.Text = value;
            }
        }

        public bool ShouldDeleteFile => checkBox1.Checked;

        private void button1_Click(object sender, EventArgs e)
        {
            DialogResult = DialogResult.OK;
        }

        private void button2_Click(object sender, EventArgs e)
        {
            DialogResult = DialogResult.Cancel;
        }
    }
}
