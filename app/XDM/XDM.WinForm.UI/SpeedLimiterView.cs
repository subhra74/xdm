using System;
using System.Windows.Forms;

namespace XDM.WinForm.UI
{
    public partial class SpeedLimiterView : UserControl
    {
        public SpeedLimiterView()
        {
            InitializeComponent();
            numericUpDown1.Maximum = Int32.MaxValue;
        }

        private void checkBox2_CheckedChanged(object sender, EventArgs e)
        {
            numericUpDown1.Enabled = checkBox2.Checked;
        }

        public bool EnableSpeedLimit
        {
            get => checkBox2.Checked;
            set => checkBox2.Checked = value;
        }

        public int SpeedLimit
        {
            get
            {
                return (int)this.numericUpDown1.Value;
            }
            set
            {
                this.numericUpDown1.Value = value;
            }
        }
    }
}
