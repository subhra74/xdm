using System;
using System.Drawing;

using System.Windows.Forms;
using Translations;
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
            button1.Text = TextResource.GetText("ND_CANCEL");
        }

        private void button1_Click(object sender, EventArgs e)
        {
            CancelClicked?.Invoke(this, EventArgs.Empty);
        }
    }
}
