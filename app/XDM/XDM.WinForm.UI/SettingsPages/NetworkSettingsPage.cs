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
using XDM.Core.Lib.Util;

#if !(NET472_OR_GREATER || NET5_0_OR_GREATER)
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
                    MessageBox.Show(this,TextResource.GetText("MSG_INVALID_PORT"));
                    e.Cancel = true;
                }
            };
            LoadTexts();
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
            Helpers.OpenWindowsProxySettings();
            //tableLayoutPanel3.Padding = new Padding(LogicalToDeviceUnits(5));
        }

        private void LoadTexts()
        {
            label1.Text = TextResource.GetText("DESC_NET1");
            label2.Text = TextResource.GetText("DESC_NET2");
            label3.Text = TextResource.GetText("NET_MAX_RETRY");
            checkBox3.Text= TextResource.GetText("MSG_SPEED_LIMIT");
            label4.Text = TextResource.GetText("DESC_NET4");
            label5.Text = TextResource.GetText("PROXY_HOST");
            label6.Text = TextResource.GetText("DESC_NET7");
            label7.Text = TextResource.GetText("PROXY_PORT");
            label8.Text = TextResource.GetText("DESC_NET8");
            button3.Text = TextResource.GetText("ND_SYSTEM_PROXY");
        }
    }
}
