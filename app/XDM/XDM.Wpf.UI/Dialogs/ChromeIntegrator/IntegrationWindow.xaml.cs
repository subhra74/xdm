using System;
using System.IO;
using System.Windows;
using XDM.Core;
using XDM.Core.BrowserMonitoring;
using XDM.Core.Util;
using XDM.Wpf.UI.Win32;
using System.Windows.Interop;

namespace XDM.Wpf.UI.Dialogs.ChromeIntegrator
{
    /// <summary>
    /// Interaction logic for IntegrationWindow.xaml
    /// </summary>
    public partial class IntegrationWindow : Window
    {
        private int page = 0;
        public IntegrationWindow(Browser browser, bool browserLaunched)
        {
            InitializeComponent();
            ApplicationContext.ApplicationEvent += ApplicationContext_ApplicationEvent;
            if (browserLaunched)
            {
                page = 1;
            }
            Page0.Browser = browser;
            Page2.Browser = browser;
            Page1.Browser = browser;
            RenderPage();
        }

        private void ApplicationContext_ApplicationEvent(object sender, ApplicationEvent e)
        {
            if (e.EventType == "ExtensionRegistered")
            {
                Dispatcher.BeginInvoke(new Action(() =>
                {
                    Page4.SuccessResult = true;
                    File.AppendAllText(System.IO.Path.Combine(Config.AppDir, "browser-integration-attempted"), "");
                    BtnBack.Visibility = Visibility.Collapsed;
                }));
            }
        }

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

        private void RenderPage()
        {
            this.Page0.Visibility = page == 0 ? Visibility.Visible : Visibility.Collapsed;
            this.Page1.Visibility = page == 1 ? Visibility.Visible : Visibility.Collapsed;
            this.Page2.Visibility = page == 2 ? Visibility.Visible : Visibility.Collapsed;
            this.Page3.Visibility = page == 3 ? Visibility.Visible : Visibility.Collapsed;
            this.Page4.Visibility = page == 4 ? Visibility.Visible : Visibility.Collapsed;
            this.BtnNext.Visibility = page == 4 ? Visibility.Collapsed : Visibility.Visible;
            this.BtnBack.Visibility = page == 0 ? Visibility.Collapsed : Visibility.Visible;
        }

        private void BtnNext_Click(object sender, RoutedEventArgs e)
        {
            if (page < 4)
            {
                page++;
            }
            RenderPage();
        }

        private void BtnBack_Click(object sender, RoutedEventArgs e)
        {
            if (page > 0)
            {
                page--;
            }
            RenderPage();
        }

        private void Window_Closed(object sender, EventArgs e)
        {
            ApplicationContext.ApplicationEvent -= ApplicationContext_ApplicationEvent;
        }

        private void BtnHelp_Click(object sender, RoutedEventArgs e)
        {
            PlatformHelper.OpenBrowser(Links.ManualExtensionInstallGuideUrl);
        }
    }
}
