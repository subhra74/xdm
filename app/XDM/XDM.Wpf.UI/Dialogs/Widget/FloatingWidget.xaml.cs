using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Forms;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;

namespace XDM.Wpf.UI.Dialogs.Widget
{
    /// <summary>
    /// Interaction logic for FloatingWidget.xaml
    /// </summary>
    public partial class FloatingWidget : Window
    {
        public FloatingWidget()
        {
            InitializeComponent();
            var rect = Screen.PrimaryScreen.WorkingArea;
            Left = rect.Width - 300;
            Top = rect.Height - 100;
        }

        private void Window_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ChangedButton == MouseButton.Left)
                this.DragMove();
        }
    }
}
