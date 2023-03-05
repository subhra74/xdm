using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using Translations;
using XDM.Core.BrowserMonitoring;

namespace XDM.Wpf.UI.Dialogs.ChromeIntegrator
{
    /// <summary>
    /// Interaction logic for Page0.xaml
    /// </summary>
    public partial class Page0 : UserControl
    {
        public Browser Browser
        {
            set
            {
                Lbl.Text = String.Format(TextResource.GetText("MSG_COPY_PASTE_EXT_URL"), value);
            }
        }
        public Page0()
        {
            InitializeComponent();
            TxtURL.Text = "chrome://extensions/";
            this.Img.Source = new BitmapImage(
                    new Uri(
                    System.IO.Path.Combine(
                    System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "images"),
                    "chrome-addressbar.jpg")));
        }

        private void BtnCopyURL_Click(object sender, RoutedEventArgs e)
        {
            Clipboard.SetText(TxtURL.Text);
        }
    }
}
