using System;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using XDM.Core.BrowserMonitoring;

namespace XDM.Wpf.UI.Dialogs.ChromeIntegrator
{
    /// <summary>
    /// Interaction logic for Page1.xaml
    /// </summary>
    public partial class Page1 : UserControl
    {
        public Page1()
        {
            InitializeComponent();
        }

        public Browser Browser
        {
            set
            {
                this.Img.Source = new BitmapImage(
                    new Uri(
                    System.IO.Path.Combine(
                    System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "images"),
                    $"{value}.jpg")));
            }
        }

        //private void Viewbox_MouseMove(object sender, MouseEventArgs e)
        //{
        //    //MessageBox.Show("ss");
        //    if (e.LeftButton == MouseButtonState.Pressed)
        //    {
        //        var browser = "chrome://extensions";
        //        switch (this.Browser)
        //        {
        //            case Browser.Brave:
        //                browser = "brave://extensions";
        //                break;
        //            case Browser.Vivaldi:
        //                browser = "vivaldi://extensions";
        //                break;
        //        }
        //        // MessageBox.Show("asda");
        //        var data = new DataObject();
        //        data.SetData(DataFormats.Text, browser);

        //        // Initiate the drag-and-drop operation.
        //        DragDrop.DoDragDrop(this.DragBorder, data, DragDropEffects.Copy | DragDropEffects.Move);
        //        //DragDrop.DoDragDrop(vb,
        //        //                     "chrome://extensions",
        //        //                     DragDropEffects.Copy);
        //    }
        //}
    }
}
