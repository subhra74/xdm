using Microsoft.Win32;
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
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Util;
using XDM.Core.Lib.UI;

namespace XDM.Wpf.UI.Dialogs.Settings
{
    /// <summary>
    /// Interaction logic for AdvancedSettingsView.xaml
    /// </summary>
    public partial class AdvancedSettingsView : UserControl, ISettingsPage
    {
        public IAppService App { get; set; }
        public AdvancedSettingsView()
        {
            InitializeComponent();
        }

        public void PopulateUI()
        {
            ChkHalt.IsChecked = Config.Instance.ShutdownAfterAllFinished;
            ChkKeepAwake.IsChecked = Config.Instance.KeepPCAwake;
            ChkRunCmd.IsChecked = Config.Instance.RunCommandAfterCompletion;
            ChkRunAntivirus.IsChecked = Config.Instance.ScanWithAntiVirus;
            ChkAutoRun.IsChecked = Helpers.IsAutoStartEnabled();

            TxtCustomCmd.Text = Config.Instance.AfterCompletionCommand;
            TxtAntiVirusCmd.Text = Config.Instance.AntiVirusExecutable;
            TxtAntiVirusArgs.Text = Config.Instance.AntiVirusArgs;
            TxtDefaultUserAgent.Text = Config.Instance.FallbackUserAgent;
        }

        public void UpdateConfig()
        {
            Config.Instance.ShutdownAfterAllFinished = ChkHalt.IsChecked ?? false;
            Config.Instance.KeepPCAwake = ChkKeepAwake.IsChecked ?? false;
            Config.Instance.RunCommandAfterCompletion = ChkRunCmd.IsChecked ?? false;
            Config.Instance.ScanWithAntiVirus = ChkRunAntivirus.IsChecked ?? false;
            Helpers.EnableAutoStart(ChkAutoRun.IsChecked ?? false);

            Config.Instance.AfterCompletionCommand = TxtCustomCmd.Text;
            Config.Instance.AntiVirusExecutable = TxtAntiVirusCmd.Text;
            Config.Instance.AntiVirusArgs = TxtAntiVirusArgs.Text;
            Config.Instance.FallbackUserAgent = TxtDefaultUserAgent.Text;
        }

        private void BtnBrowse_Click(object sender, RoutedEventArgs e)
        {
            var fd = new OpenFileDialog();
            var ret = fd.ShowDialog();
            if (ret.HasValue && ret.Value)
            {
                TxtAntiVirusCmd.Text = fd.FileName;
            }
        }

        private void BtnUserAgentReset_Click(object sender, RoutedEventArgs e)
        {
            Config.Instance.FallbackUserAgent = Config.DefaultFallbackUserAgent;
        }
    }
}
