using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using XDM.Wpf.UI.Common;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.DeleteConfirm
{
    /// <summary>
    /// Interaction logic for DeleteConfirmDialog.xaml
    /// </summary>
    public partial class DeleteConfirmDialog : Window, IDialog
    {
        public bool Result { get; set; } = false;

        public DeleteConfirmDialog()
        {
            InitializeComponent();
        }

        public string DescriptionText
        {
            set
            {
                TxtLabel.Text = value;
            }
        }

        public bool ShouldDeleteFile => ChkDiskDel.IsChecked ?? false;

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            NativeMethods.DisableMinMaxButton(this);

#if NET45_OR_GREATER
            if (XDM.Wpf.UI.App.Skin == Skin.Dark)
            {
                var helper = new WindowInteropHelper(this);
                helper.EnsureHandle();
                DarkModeHelper.UseImmersiveDarkMode(helper.Handle, true);
            }
#endif
        }

        private void BtnDelete_Click(object sender, RoutedEventArgs e)
        {
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
