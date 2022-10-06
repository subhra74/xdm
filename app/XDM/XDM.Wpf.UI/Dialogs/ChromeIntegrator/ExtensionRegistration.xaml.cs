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
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using Translations;
using XDM.Core.BrowserMonitoring;
using XDM.Core.Util;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.ChromeIntegrator
{
    /// <summary>
    /// Interaction logic for ExtensionRegistration.xaml
    /// </summary>
    public partial class ExtensionRegistration : Window
    {
        public ExtensionRegistration()
        {
            InitializeComponent();
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

        private void BtnOk_Click(object sender, RoutedEventArgs e)
        {
            if (string.IsNullOrEmpty(TxtExtId.Text))
            {
                MessageBox.Show(this, TextResource.GetText("MSG_FIELD_BLANK"));
                return;
            }
            ExtensionRegistrationHelper.AddExtension("chrome-extension://" + TxtExtId.Text);
            NativeMessagingHostConfigurer.InstallNativeMessagingHostForWindows(Browser.Chrome);
            Close();
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            this.Close();
        }
    }
}
