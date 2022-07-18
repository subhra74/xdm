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
using System.Windows.Interop;
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
            var indexFile = System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, @"Lang\index.txt");
            if (File.Exists(indexFile))
            {
                var lines = File.ReadAllLines(indexFile);
                var items = new List<FileMap>(lines.Length);
                FileMap selection = default;
                foreach (var line in lines)
                {
                    var index = line.IndexOf("=");
                    if (index > 0)
                    {
                        var name = line.Substring(0, index);
                        var value = line.Substring(index + 1);
                        var fm = new FileMap
                        {
                            Name = name,
                            File = value
                        };
                        items.Add(fm);
                        if (name == Config.Instance.Language)
                        {
                            selection = fm;
                        }
                    }
                }
                CmbLanguage.ItemsSource = items;
                CmbLanguage.SelectedItem = selection;
            }
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

    internal struct FileMap
    {
        public string Name, File;
        public override string ToString()
        {
            return Name;
        }
    }
}
