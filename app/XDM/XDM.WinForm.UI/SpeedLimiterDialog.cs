using System;
using System.Windows.Forms;

#if !(NET472_OR_GREATER||NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI
{
    public partial class SpeedLimiterDialog : Form
    {
        public event EventHandler OkClicked;

        public SpeedLimiterDialog()
        {
            InitializeComponent();
            button2.Padding = new Padding(LogicalToDeviceUnits(20), LogicalToDeviceUnits(2),
                LogicalToDeviceUnits(20), LogicalToDeviceUnits(2));
            button1.Padding = new Padding(LogicalToDeviceUnits(10), LogicalToDeviceUnits(2),
                LogicalToDeviceUnits(10), LogicalToDeviceUnits(2));
            flowLayoutPanel1.Padding = new Padding(LogicalToDeviceUnits(2));
            button1.Margin = button2.Margin = new Padding(LogicalToDeviceUnits(3));
        }

        public int SpeedLimit
        {
            get => this.speedLimiterView1.SpeedLimit;
            set => this.speedLimiterView1.SpeedLimit = value;
        }

        public bool EnableSpeedLimit
        {
            get => this.speedLimiterView1.EnableSpeedLimit;
            set => this.speedLimiterView1.EnableSpeedLimit = value;
        }

        private void button1_Click(object sender, EventArgs e)
        {
            Close();
        }

        private void button2_Click(object sender, EventArgs e)
        {
            OkClicked?.Invoke(sender, EventArgs.Empty);
            Close();
        }
    }
}
