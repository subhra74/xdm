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
using XDM.Core;
using XDM.Core.Util;
using XDM.Wpf.UI.Common;
using XDM.Wpf.UI.Win32;
using XDM.Core.UI;

namespace XDM.Wpf.UI.Dialogs.Settings
{
    /// <summary>
    /// Interaction logic for SettingsWindow.xaml
    /// </summary>
    public partial class SettingsWindow : Window, IDialog
    {
        private ISettingsPage[] pages;

        public SettingsWindow(int selectedPageIndex)
        {
            InitializeComponent();
            pages = new ISettingsPage[]
            {
                BrowserMonitoringView,
                GeneralSettingsView,
                NetworkSettingsView,
                PasswordManagerView,
                AdvancedSettingsView
            };
            LbTitles.SelectedIndex = selectedPageIndex;
            foreach (var page in pages)
            {
                page.PopulateUI();
            }
            GeneralSettingsView.Window = this;
            PasswordManagerView.Window = this;
        }

        public SettingsWindow() : this(1) { }

        public bool Result { get; set; } = false;

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            NativeMethods.DisableMinMaxButton(this);

#if NET45_OR_GREATER
            if (XDM.Wpf.UI.App.Skin == Skin.Dark)
            {
                var helper = new WindowInteropHelper(this);
                helper.EnsureHandle();
                DarkModeHelper.UseImmersiveDarkMode(helper.Handle, true);
            }
#endif
        }

        private void BtnOK_Click(object sender, RoutedEventArgs e)
        {
            foreach (var page in pages)
            {
                page.UpdateConfig();
            }
            Config.SaveConfig();
            ApplicationContext.BroadcastConfigChange();
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
