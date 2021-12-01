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
    public partial class NetworkSettingsPage : UserControl, ISettingsPage
    {

        public NetworkSettingsPage()
        {
            InitializeComponent();
            AutoScrollMinSize = tableLayoutPanel1.Size;
            comboBox1.SelectedIndexChanged += (_, _) =>
            {
                textBox3.Enabled = textBox4.Enabled = textBox5.Enabled = textBox6.Enabled = comboBox1.SelectedIndex == 2;
            };
            textBox4.Validating += (o, e) =>
            {
                if (!Int32.TryParse(textBox4.Text, out _))
                {
                    MessageBox.Show("Invalid port");
                    e.Cancel = true;
                }
            };
        }

        public void PopulateUI()
        {
            numericUpDown1.Value = Config.Instance.NetworkTimeout;
            numericUpDown2.Value = Config.Instance.MaxSegments;
            numericUpDown3.Value = Config.Instance.MaxRetry;
            numericUpDown4.Value = Config.Instance.DefaltDownloadSpeed;
            checkBox3.Checked = Config.Instance.EnableSpeedLimit;
            comboBox1.SelectedIndex = (int)(Config.Instance.Proxy?.ProxyType ?? ProxyType.System);
            textBox3.Text = Config.Instance.Proxy?.Host;
            textBox4.Text = (Config.Instance.Proxy?.Port ?? 0).ToString();
            textBox5.Text = Config.Instance.Proxy?.UserName;
            textBox6.Text = Config.Instance.Proxy?.Password;
        }

        public void UpdateConfig()
        {
            Config.Instance.NetworkTimeout = (int)numericUpDown1.Value;
            Config.Instance.MaxSegments = (int)numericUpDown2.Value;
            Config.Instance.MaxRetry = (int)numericUpDown3.Value;
            Config.Instance.DefaltDownloadSpeed = (int)numericUpDown4.Value;
            Config.Instance.EnableSpeedLimit = checkBox3.Checked;
            Int32.TryParse(textBox4.Text, out int port);
            Config.Instance.Proxy = new ProxyInfo
            {
                ProxyType = (ProxyType)comboBox1.SelectedIndex,
                Host = textBox3.Text,
                UserName = textBox5.Text,
                Password = textBox6.Text,
                Port = port
            };
        }

        private void button3_Click(object sender, EventArgs e)
        {
            tableLayoutPanel3.Padding = new Padding(LogicalToDeviceUnits(5));
        }
    }
}
