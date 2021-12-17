using System;
using System.Diagnostics;

using System.Windows.Forms;
using Translations;
using XDM.Core.Lib.Common;

#if !(NET472_OR_GREATER || NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI
{
    public partial class AdvancedDownloadDialog : Form
    {
        public AuthenticationInfo? Authentication
        {
            get
            {
                if (string.IsNullOrEmpty(textBox1.Text))
                {
                    return null;
                }
                return new AuthenticationInfo
                {
                    UserName = textBox1.Text,
                    Password = textBox2.Text
                };
            }
            set
            {
                if (value.HasValue)
                {
                    textBox1.Text = value.Value.UserName;
                    textBox2.Text = value.Value.Password;
                }
            }
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

        public ProxyInfo? Proxy
        {
            get
            {
                if (comboBox1.SelectedIndex == 1)
                {
                    return new ProxyInfo { ProxyType = ProxyType.Direct };
                }
                if (comboBox1.SelectedIndex == 0)
                {
                    return new ProxyInfo { ProxyType = ProxyType.System };
                }
                if (comboBox1.SelectedIndex == 2 &&
                    !string.IsNullOrEmpty(textBox3.Text) &&
                    Int32.TryParse(textBox4.Text, out _))
                {
                    return new ProxyInfo
                    {
                        ProxyType = ProxyType.Custom,
                        Host = textBox3.Text,
                        Port = Int32.Parse(textBox4.Text),
                        UserName = textBox5.Text,
                        Password = textBox6.Text
                    };
                }
                return null;
            }
            set
            {
                if (value.HasValue)
                {
                    comboBox1.SelectedIndex = (int)(Config.Instance.Proxy?.ProxyType ?? ProxyType.System);
                    textBox3.Text = Config.Instance.Proxy?.Host;
                    textBox4.Text = (Config.Instance.Proxy?.Port ?? 0).ToString();
                    textBox5.Text = Config.Instance.Proxy?.UserName;
                    textBox6.Text = Config.Instance.Proxy?.Password;
                }
                else
                {
                    SetProxy(value ?? Config.Instance.Proxy);
                }
            }
        }

        private void SetProxy(ProxyInfo? proxy)
        {
            comboBox1.SelectedIndex = (int)(proxy?.ProxyType ?? 0);
            textBox3.Text = proxy?.Host;
            textBox4.Text = proxy?.Port.ToString();
            textBox5.Text = proxy?.UserName;
            textBox6.Text = proxy?.Password;
        }

        public AdvancedDownloadDialog()
        {
            InitializeComponent();
            comboBox1.SelectedIndexChanged += (_, _) =>
            {
                textBox3.Enabled = textBox4.Enabled = textBox5.Enabled
                = textBox6.Enabled = comboBox1.SelectedIndex == 2;
            };
            textBox4.Validating += (o, e) =>
            {
                if (!Int32.TryParse(textBox4.Text, out _))
                {
                    MessageBox.Show(TextResource.GetText("MSG_INVALID_PORT"));
                    e.Cancel = true;
                }
            };

            button3.Margin = new Padding(0, LogicalToDeviceUnits(10), 0, LogicalToDeviceUnits(10));
            LoadTexts();
        }

        private void button1_Click(object sender, EventArgs e)
        {
            DialogResult = DialogResult.OK;
        }

        private void button3_Click(object sender, EventArgs e)
        {
            if (Environment.OSVersion.Version.Major == 10)
            {
                var psi = new ProcessStartInfo
                {
                    FileName = "ms-settings:network-proxy",
                    UseShellExecute = true
                };
                Process.Start(psi);
            }
            else
            {
                var psi = new ProcessStartInfo
                {
                    FileName = "rundll32.exe",
                    Arguments = "inetcpl.cpl,LaunchConnectionDialog",
                    UseShellExecute = true
                };
                Process.Start(psi);
            }
        }

        private void button2_Click(object sender, EventArgs e)
        {
            DialogResult = DialogResult.Cancel;
        }

        private void LoadTexts()
        {
            tabPage1.Text = TextResource.GetText("ND_AUTH");
            tabPage2.Text = TextResource.GetText("DESC_NET4");
            tabPage4.Text = TextResource.GetText("MENU_SPEED_LIMITER");

            label1.Text = TextResource.GetText("DESC_USER");
            label2.Text = TextResource.GetText("DESC_PASS");
            checkBox1.Text = TextResource.GetText("ND_AUTH_REMEMBER");

            label3.Text = TextResource.GetText("DESC_NET4");
            label4.Text = TextResource.GetText("PROXY_HOST");
            label5.Text = TextResource.GetText("DESC_NET7");
            label6.Text = TextResource.GetText("PROXY_PORT");
            label7.Text = TextResource.GetText("DESC_NET8");
            button3.Text = TextResource.GetText("ND_SYSTEM_PROXY");
        }
    }
}
