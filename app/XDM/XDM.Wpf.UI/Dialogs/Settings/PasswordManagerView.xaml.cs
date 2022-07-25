using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
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
using XDM.Wpf.UI.Win32;
using XDM.Core.UI;

namespace XDM.Wpf.UI.Dialogs.Settings
{
    /// <summary>
    /// Interaction logic for PasswordManagerView.xaml
    /// </summary>
    public partial class PasswordManagerView : UserControl, ISettingsPage
    {
        public IAppService App { get; set; }
        public Window Window { get; set; }
        private ObservableCollection<PasswordEntry> passwords = new();

        public PasswordManagerView()
        {
            InitializeComponent();
        }

        public void PopulateUI()
        {
            foreach (var password in Config.Instance.UserCredentials)
            {
                passwords.Add(password);
            }
            LvPasswords.ItemsSource = passwords;
        }

        public void UpdateConfig()
        {
            Config.Instance.UserCredentials = new List<PasswordEntry>(this.passwords);
        }

        private void CatAdd_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new PasswordWindow { Owner = Window };
            var ret = dlg.ShowDialog(Window);
            if (ret.HasValue && ret.Value)
            {
                passwords.Add(new PasswordEntry
                {
                    Host = dlg.HostName,
                    User = dlg.UserName,
                    Password = dlg.Password
                });
            }
        }

        private void CatEdit_Click(object sender, RoutedEventArgs e)
        {
            var index = LvPasswords.SelectedIndex;
            if (index >= 0)
            {
                var password = passwords[index];
                var dlg = new PasswordWindow { Owner = Window };
                dlg.SetPassword(password);
                var ret = dlg.ShowDialog(Window);
                if (ret.HasValue && ret.Value)
                {
                    passwords[index] = new PasswordEntry
                    {
                        Host = dlg.HostName,
                        User = dlg.UserName,
                        Password = dlg.Password
                    };
                }
            }
        }

        private void CatDel_Click(object sender, RoutedEventArgs e)
        {
            var index = LvPasswords.SelectedIndex;
            if (index >= 0)
            {
                passwords.RemoveAt(index);
            }
        }
    }
}
