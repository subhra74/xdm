using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Navigation;
using System.Windows.Shapes;

namespace XDM.Wpf.UI.Dialogs.ChromeIntegrator
{
    /// <summary>
    /// Interaction logic for Page4.xaml
    /// </summary>
    public partial class Page4 : UserControl
    {
        public Page4()
        {
            InitializeComponent();
            this.Img.Source = new BitmapImage(
                    new Uri(
                    System.IO.Path.Combine(
                    System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "images"),
                    "pin-ext.jpg")));
            this.SuccessResult = true;
        }

        public bool SuccessResult
        {
            set
            {
                MsgSuccess.Visibility = value ? Visibility.Visible : Visibility.Collapsed;
                MsgInfo.Visibility = value ? Visibility.Visible : Visibility.Collapsed;
                Img.Visibility = value ? Visibility.Visible : Visibility.Collapsed;
                MsgFail.Visibility = value ? Visibility.Collapsed : Visibility.Visible;
            }
        }
    }
}
