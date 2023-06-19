using System;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using XDM.Core.BrowserMonitoring;

namespace XDM.Wpf.UI.Dialogs.ChromeIntegrator
{
    /// <summary>
    /// Interaction logic for Page2.xaml
    /// </summary>
    public partial class Page2 : UserControl
    {
        public Browser Browser
        {
            set
            {
                this.Img.Source = new BitmapImage(
                    new Uri(
                    System.IO.Path.Combine(
                    System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "images"),
                    $"load_unpacked.jpg")));
            }
        }
        public Page2()
        {
            InitializeComponent();
        }
    }
}
