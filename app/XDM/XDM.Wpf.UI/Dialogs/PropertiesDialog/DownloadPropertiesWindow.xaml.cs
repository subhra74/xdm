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

namespace XDM.Wpf.UI.Dialogs.PropertiesDialog
{
    /// <summary>
    /// Interaction logic for DownloadPropertiesWindow.xaml
    /// </summary>
    public partial class DownloadPropertiesWindow : Window, IDialog
    {
        public DownloadPropertiesWindow()
        {
            InitializeComponent();
        }

        public bool Result { get; set; }

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            NativeMethods.DisableMinMaxButton(this);

#if NET45_OR_GREATER
            if (App.Skin == Skin.Dark)
            {
                var helper = new WindowInteropHelper(this);
                helper.EnsureHandle();
                DarkModeHelper.UseImmersiveDarkMode(helper.Handle, true);
            }
#endif
        }

        public string FileName { set => TxtName.Text = value; }
        public string Folder { set => TxtSaveIn.Text = value; }
        public string Address { set => TxtAddress.Text = value; }
        public string FileSize { set => TxtSize.Text = value; }
        public string DateAdded { set => TxtDate.Text = value; }
        public string DownloadType { set => TxtType.Text = value; }
        public string Referer { set => TxtReferer.Text = value; }

        public string Cookies
        {
            set
            {
                TxtCookie.Text = value;
            }
        }

        public Dictionary<string, List<string>> Headers
        {
            set
            {
                if (value != null)
                {
                    var textBuf = new StringBuilder();
                    foreach (var key in value.Keys)
                    {
                        foreach (var val in value[key])
                        {
                            textBuf.Append(key + ": " + val + "\r\n");
                        }
                    }
                    TxtHeaders.Text = textBuf.ToString();
                }
            }
        }
    }
}
