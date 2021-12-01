using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Text;

using System.Windows.Forms;
using XDM.WinForm.UI.FormHelper;

namespace XDM.WinForm.UI.VideoDownloaderPages
{
    public partial class VideoDownloaderPage3 : UserControl
    {
        public EventHandler CancelClicked;
        public VideoDownloaderPage3()
        {
            InitializeComponent();
            if (!AppWinPeer.AppsUseLightTheme)
            {
                var bg = Color.FromArgb(60, 60, 60);
                var fg = Color.White;
                DarkModeHelper.StyleFlatButton(button1, bg, fg);
            }
        }

        private void button1_Click(object sender, EventArgs e)
        {
            CancelClicked?.Invoke(this, EventArgs.Empty);
        }
    }
}
