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
using System.Windows.Shapes;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Util;
using XDM.Wpf.UI.Common;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.Settings
{
    /// <summary>
    /// Interaction logic for SettingsWindow.xaml
    /// </summary>
    public partial class SettingsWindow : Window, IDialog
    {
        private ISettingsPage[] pages;
        private IApp app;

        public SettingsWindow(IApp app)
        {
            InitializeComponent();
            this.app = app;
            pages = new ISettingsPage[]
            {
                BrowserMonitoringView,
                GeneralSettingsView,
                NetworkSettingsView,
                PasswordManagerView,
                AdvancedSettingsView
            };
            LbTitles.SelectedIndex = 1;
            foreach (var page in pages)
            {
                page.App = app;
                page.PopulateUI();
            }
            GeneralSettingsView.Window = this;
            PasswordManagerView.Window = this;
        }

        public bool Result { get; set; } = false;

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            NativeMethods.DisableMinMaxButton(this);
        }

        private void BtnOK_Click(object sender, RoutedEventArgs e)
        {
            foreach (var page in pages)
            {
                page.UpdateConfig();
            }
            Config.SaveConfig();
            app.ApplyConfig();
            Close();
            Helpers.RunGC();
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            Close();
        }

        private void LbTitles_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            var index = LbTitles.SelectedIndex;
            ShowPage(index);
        }

        private void ShowPage(int index)
        {
            var page = pages[index];
            foreach (UserControl pg in pages)
            {
                if (pg == page)
                {
                    pg.Visibility = Visibility.Visible;
                    continue;
                }
                pg.Visibility = Visibility.Collapsed;
            }
        }
    }
}
