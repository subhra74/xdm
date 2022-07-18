using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Util;
using XDM.Wpf.UI.Common;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.AdvancedDownloadOption
{
    /// <summary>
    /// Interaction logic for AdvancedDownloadOption.xaml
    /// </summary>
    public partial class AdvancedDownloadOptionDialog : Window, IDialog
    {
        public AdvancedDownloadOptionDialog()
        {
            InitializeComponent();
        }

        public AuthenticationInfo? Authentication
        {
            get
            {
                if (string.IsNullOrEmpty(TxtUserName.Text))
                {
                    return null;
                }
                return new AuthenticationInfo
                {
                    UserName = TxtUserName.Text,
                    Password = TxtPassword.Password
                };
            }
            set
            {
                if (value.HasValue)
                {
                    TxtUserName.Text = value.Value.UserName;
                    TxtPassword.Password = value.Value.Password;
                }
            }
        }

        public ProxyInfo? Proxy
        {
            get
            {
                if (CmbProxyType.SelectedIndex == 1)
                {
                    return new ProxyInfo { ProxyType = ProxyType.Direct };
                }
                if (CmbProxyType.SelectedIndex == 0)
                {
                    return new ProxyInfo { ProxyType = ProxyType.System };
                }
                if (CmbProxyType.SelectedIndex == 2 &&
                    !string.IsNullOrEmpty(TxtProxyHost.Text) &&
                    Int32.TryParse(TxtProxyPort.Text, out _))
                {
                    return new ProxyInfo
                    {
                        ProxyType = ProxyType.Custom,
                        Host = TxtProxyHost.Text,
                        Port = Int32.Parse(TxtProxyPort.Text),
                        UserName = TxtProxyUser.Text,
                        Password = TxtProxyPassword.Password
                    };
                }
                return null;
            }
            set
            {
                SetProxy(value ?? Config.Instance.Proxy);
            }
        }

        private void SetProxy(ProxyInfo? proxy)
        {
            CmbProxyType.SelectedIndex = (int)(proxy?.ProxyType ?? 0);
            TxtProxyHost.Text = proxy?.Host;
            TxtProxyPort.Text = proxy?.Port.ToString();
            TxtProxyUser.Text = proxy?.UserName;
            TxtProxyPassword.Password = proxy?.Password;
        }

        public int SpeedLimit
        {
            get => this.SpeedLimiter.SpeedLimit;
            set => this.SpeedLimiter.SpeedLimit = value;
        }

        public bool EnableSpeedLimit
        {
            get => this.SpeedLimiter.IsSpeedLimitEnabled;
            set => this.SpeedLimiter.IsSpeedLimitEnabled = value;
        }

        public bool Result { get; set; } = false;

        private void BtnOK_Click(object sender, RoutedEventArgs e)
        {
            Result = true;
            Close();
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            Result = false;
            Close();
        }

        private void BtnSystemProxy_Click(object sender, RoutedEventArgs e)
        {
            Helpers.OpenWindowsProxySettings();
        }

        private void CmbProxyType_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            TxtProxyUser.IsEnabled = TxtProxyPassword.IsEnabled = TxtProxyHost.IsEnabled =
                TxtProxyPort.IsEnabled = CmbProxyType.SelectedIndex == 2;
        }

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);

            NativeMethods.DisableMinMaxButton(this);
#if NET45_OR_GREATER
            if (App.Skin == Skin.Dark)
            {
                var helper = new WindowInteropHelper(this);
                helper.EnsureHandle();
                DarkModeHelper.UseImmersiveDarkMode(helper.Handle, true);
            }
#endif
        }

    }
}
