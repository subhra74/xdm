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

namespace XDM.Wpf.UI.Dialogs.VideoDownloader
{
    /// <summary>
    /// Interaction logic for VideoDownloaderPage1.xaml
    /// </summary>
    public partial class VideoDownloaderPage1 : UserControl
    {
        public string UrlText { get => TxtUrl.Text; set => TxtUrl.Text = value; }
        public string UserNameText => TxtUserName.Text;
        public string PasswordText => TxtPassword.Password;
        public bool UseCredentials => ChkAuth.IsChecked.HasValue ? ChkAuth.IsChecked.Value : false;

        public EventHandler? SearchClicked;

        public VideoDownloaderPage1()
        {
            InitializeComponent();
        }

        public void InitPage(IAppUI appUi)
        {

        }

        private void Button_Click(object sender, RoutedEventArgs e)
        {
            SearchClicked?.Invoke(this, EventArgs.Empty);
        }
    }
}
