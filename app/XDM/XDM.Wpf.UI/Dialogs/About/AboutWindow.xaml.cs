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
using XDM.Core.Lib.Util;
using XDM.Wpf.UI.Common;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.About
{
    /// <summary>
    /// Interaction logic for AboutWindow.xaml
    /// </summary>
    public partial class AboutWindow : Window, IDialog
    {
        public AboutWindow()
        {
            InitializeComponent();
        }

        public bool Result { get; set; }

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

        private void TextBlock_MouseDown(object sender, MouseButtonEventArgs e)
        {
            Helpers.OpenBrowser("https://xtremedownloadmanager.com/");
        }
    }
}
