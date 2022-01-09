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

namespace XDM.Wpf.UI.Dialogs.NewDownload
{
    /// <summary>
    /// Interaction logic for NewDownloadWindow.xaml
    /// </summary>
    public partial class NewDownloadWindow : Window
    {
        public NewDownloadWindow()
        {
            InitializeComponent();
        }

#if NET45_OR_GREATER
        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);

            if (App.Skin == Skin.Dark)
            {
                var helper = new WindowInteropHelper(this);
                helper.EnsureHandle();
                DarkModeHelper.UseImmersiveDarkMode(helper.Handle, true);
            }
        }
#endif
    }
}
