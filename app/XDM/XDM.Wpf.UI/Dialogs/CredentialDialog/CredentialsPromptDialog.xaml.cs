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
using Translations;
using XDM.Core.Lib.Common;
using XDM.Wpf.UI.Common;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.CredentialDialog
{
    /// <summary>
    /// Interaction logic for CredentialsPromptDialog.xaml
    /// </summary>
    public partial class CredentialsPromptDialog : Window, IDialog
    {
        public AuthenticationInfo? Credentials => new AuthenticationInfo
        {
            UserName = TxtUserName.Text,
            Password = TxtPassword.Password
        };

        public bool Result { get; set; } = false;

        public string PromptText { set => TxtMessage.Text = value; }

        public CredentialsPromptDialog()
        {
            InitializeComponent();
        }

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            NativeMethods.DisableMinMaxButton(this);
        }

        private void BtnOk_Click(object sender, RoutedEventArgs e)
        {
            if (string.IsNullOrEmpty(TxtUserName.Text))
            {
                MessageBox.Show(this, TextResource.GetText("MSG_NO_USERNAME"));
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
