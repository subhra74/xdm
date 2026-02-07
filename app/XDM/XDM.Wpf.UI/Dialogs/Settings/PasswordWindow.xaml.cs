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
using Translations;
using XDM.Core;
using XDM.Wpf.UI.Common;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.Settings
{
    /// <summary>
    /// Interaction logic for PasswordWindow.xaml
    /// </summary>
    public partial class PasswordWindow : Window, IDialog
    {
        public bool Result { get; set; } = false;

        public PasswordWindow()
        {
            InitializeComponent();
        }

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

        public void SetPassword(PasswordEntry password)
        {
            HostName = password.Host;
            UserName = password.User;
            Password = password.Password;
        }

        public string HostName
        {
            get => TxtHost.Text;
            set => TxtHost.Text = value;
        }

        public string UserName
        {
            get => TxtUserName.Text;
            set => TxtUserName.Text = value;
        }

        public string Password
        {
            get => TxtPassword.Password;
            set => TxtPassword.Password = value;
        }

        private void BtnOk_Click(object sender, RoutedEventArgs e)
        {
            if (string.IsNullOrEmpty(HostName))
            {
                MessageBox.Show(TextResource.GetText("MSG_HOST_NAME_MISSING"));
                return;
            }
            if (string.IsNullOrEmpty(UserName))
            {
                MessageBox.Show(TextResource.GetText("MSG_NO_USERNAME"));
                return;
            }
            Result = true;
            Close();
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            Result = false;
            Close();
        }
    }
}
