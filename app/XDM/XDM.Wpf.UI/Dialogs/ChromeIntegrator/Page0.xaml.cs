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
    /// Interaction logic for Page0.xaml
    /// </summary>
    public partial class Page0 : UserControl
    {
        public Page0()
        {
            InitializeComponent();
            TxtURL.Text = "chrome://extensions/";
        }

        private void BtnCopyURL_Click(object sender, RoutedEventArgs e)
        {
            Clipboard.SetText(TxtURL.Text);
        }
    }
}
