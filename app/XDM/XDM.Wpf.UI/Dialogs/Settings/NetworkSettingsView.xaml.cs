using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Navigation;
using System.Windows.Shapes;
using XDM.Core;
using XDM.Core.Util;
using XDM.Core.UI;

namespace XDM.Wpf.UI.Dialogs.Settings
{
    /// <summary>
    /// Interaction logic for NetworkSettingsView.xaml
    /// </summary>
    public partial class NetworkSettingsView : UserControl, ISettingsPage
    {
        public NetworkSettingsView()
        {
            InitializeComponent();
            CmbTimeOut.ItemsSource = new List<int>(Enumerable.Range(1, 300));
            CmbMaxSegments.ItemsSource = new List<int>(Enumerable.Range(1, 64));
            CmbMaxRetry.ItemsSource = new List<int>(Enumerable.Range(1, 100));
        }

        public void PopulateUI()
        {
            CmbTimeOut.SelectedItem = Config.Instance.NetworkTimeout;
            CmbMaxSegments.SelectedItem = Config.Instance.MaxSegments;
            CmbMaxRetry.SelectedItem = Config.Instance.MaxRetry;
            TxtMaxSpeedLimit.Text = Config.Instance.DefaltDownloadSpeed.ToString();
            ChkEnableSpeedLimit.IsChecked = Config.Instance.EnableSpeedLimit;
            CmbProxyType.SelectedIndex = (int)(Config.Instance.Proxy?.ProxyType ?? ProxyType.System);
            TxtProxyHost.Text = Config.Instance.Proxy?.Host;
            TxtProxyPort.Text = (Config.Instance.Proxy?.Port ?? 0).ToString();
            TxtProxyUser.Text = Config.Instance.Proxy?.UserName;
            TxtProxyPassword.Password = Config.Instance.Proxy?.Password;
        }

        public void UpdateConfig()
        {
            Config.Instance.NetworkTimeout = (int)CmbTimeOut.SelectedItem;
            Config.Instance.MaxSegments = (int)CmbMaxSegments.SelectedItem;
            Config.Instance.MaxRetry = (int)CmbMaxRetry.SelectedItem;
            if (Int32.TryParse(TxtMaxSpeedLimit.Text, out int speed))
            {
                Config.Instance.DefaltDownloadSpeed = speed;
            }
            Config.Instance.EnableSpeedLimit = ChkEnableSpeedLimit.IsChecked ?? false;
            Int32.TryParse(TxtProxyPort.Text, out int port);
            Config.Instance.Proxy = new ProxyInfo
            {
                ProxyType = (ProxyType)CmbProxyType.SelectedIndex,
                Host = TxtProxyHost.Text,
                UserName = TxtProxyUser.Text,
                Password = TxtProxyPassword.Password,
                Port = port
            };
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

        private void TxtSpeedLimit_PreviewTextInput(object sender, TextCompositionEventArgs e)
        {
            e.Handled = !Int32.TryParse(e.Text, out _);
        }

        private void TxtSpeedLimit_Pasting(object sender, DataObjectPastingEventArgs e)
        {
            if (e.DataObject.GetDataPresent(typeof(string)))
            {
                string text = (string)e.DataObject.GetData(typeof(string));
                if (!Int32.TryParse(text, out _))
                {
                    e.CancelCommand();
                }
            }
            else
            {
                e.CancelCommand();
            }
        }

        private void TxtSpeedLimit_LostFocus(object sender, RoutedEventArgs e)
        {
            var valid = true;
            if (string.IsNullOrEmpty(TxtMaxSpeedLimit.Text))
            {
                valid = false;
            }
            if (!Int32.TryParse(TxtMaxSpeedLimit.Text, out _))
            {
                valid = false;
            }
            if (!valid)
            {
                TxtMaxSpeedLimit.Text = "0";
            }
        }
    }
}
