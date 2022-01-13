using System;
using System.Collections.Generic;
using System.IO;
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
using XDM.Wpf.UI.Common;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.LanguageSettings
{
    /// <summary>
    /// Interaction logic for LanguageSettingsWindow.xaml
    /// </summary>
    public partial class LanguageSettingsWindow : Window, IDialog
    {
        public bool Result { get; set; } = false;

        public LanguageSettingsWindow()
        {
            InitializeComponent();
            CmbLanguage.ItemsSource = Directory.GetFiles(
                System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Lang"))
                ?.Select(x => System.IO.Path.GetFileNameWithoutExtension(x));
            CmbLanguage.SelectedItem = Config.Instance.Language;
        }

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            NativeMethods.DisableMinMaxButton(this);
        }

        private void BtnOk_Click(object sender, RoutedEventArgs e)
        {
            if (CmbLanguage.SelectedIndex >= 0)
            {
                Config.Instance.Language = CmbLanguage.SelectedItem.ToString();
                Config.SaveConfig();
                Close();
                Result = true;
            }
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            Close();
            Result = false;
        }
    }
}
