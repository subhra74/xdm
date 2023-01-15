using System;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using XDM.Core;
using XDM.Core.Util;
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
            this.TxtAppVersion.Text = AppInfo.APP_VERSION_TEXT;
            this.TxtCopyright.Text = AppInfo.APP_COPYRIGHT_TEXT;
            this.TxtWebsite.Text = AppInfo.APP_HOMEPAGE_TEXT;
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
            PlatformHelper.OpenBrowser(Links.HomePageUrl);
        }
    }
}
