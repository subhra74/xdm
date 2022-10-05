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
    /// Interaction logic for Page1.xaml
    /// </summary>
    public partial class Page1 : UserControl
    {
        public Page1()
        {
            InitializeComponent();
        }

        private void Viewbox_MouseMove(object sender, MouseEventArgs e)
        {
            //MessageBox.Show("ss");
            if (e.LeftButton == MouseButtonState.Pressed)
            {
                // MessageBox.Show("asda");
                var data = new DataObject();
                data.SetData(DataFormats.Text, "chrome://extensions");

                // Initiate the drag-and-drop operation.
                DragDrop.DoDragDrop(this.DragBorder, data, DragDropEffects.Copy | DragDropEffects.Move);
                //DragDrop.DoDragDrop(vb,
                //                     "chrome://extensions",
                //                     DragDropEffects.Copy);
            }
        }
    }
}
