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

namespace XDM.Wpf.UI.Dialogs.VideoDownloader
{
    /// <summary>
    /// Interaction logic for VideoDownloaderPage2.xaml
    /// </summary>
    public partial class VideoDownloaderPage2 : UserControl
    {
        public EventHandler? CancelClicked;
        public VideoDownloaderPage2()
        {
            InitializeComponent();
        }

        private void Button_Click(object sender, RoutedEventArgs e)
        {
            CancelClicked?.Invoke(this, EventArgs.Empty);
        }
    }
}
