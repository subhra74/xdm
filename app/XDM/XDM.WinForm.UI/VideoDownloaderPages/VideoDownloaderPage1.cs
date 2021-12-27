using System;
using System.Drawing;

using System.Windows.Forms;
using Translations;
using XDM.Core.Lib.Common;
using XDM.WinForm.UI.FormHelper;

#if !(NET472_OR_GREATER || NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI.VideoDownloaderPages
{
    public partial class VideoDownloaderPage1 : UserControl
    {
        public string UrlText { get => textBox1.Text; }
        public string UserNameText { get => textBox2.Text; }
        public string PasswordText { get => textBox3.Text; }
        public bool UseCredentials { get => checkBox1.Checked; }

        public EventHandler SearchClicked;
        private IFormColors colors;

        public VideoDownloaderPage1(Font searchFont, IAppUI appUi)
        {
            InitializeComponent();
            button1.Font = searchFont;
            button1.Text = ((char)Int32.Parse("f002", System.Globalization.NumberStyles.HexNumber)).ToString();
            textBox2.Visible = textBox3.Visible = checkBox1.Checked;
            label2.Visible = label3.Visible = checkBox1.Checked;
            var url = appUi.GetUrlFromClipboard();
            if (url != null)
            {
                textBox1.Text = url;
            }
            if (!AppWinPeer.AppsUseLightTheme)
            {
                var colors = new FormColorsDark();
                label1.ForeColor = checkBox1.ForeColor = label2.ForeColor = label3.ForeColor = Color.White;
                DarkModeHelper.StyleFlatButton(button1, colors.BackColor, colors.ForeColor);
                var bg = colors.BackColor;
                tableLayoutPanel1.BackColor = bg;
                DarkModeHelper.StyleFlatTextBox(textBox1, colors);
                DarkModeHelper.StyleFlatTextBox(textBox2, colors);
                DarkModeHelper.StyleFlatTextBox(textBox3, colors);
            }

            label1.Text = TextResource.GetText("VID_PASTE_URL");
            checkBox1.Text = TextResource.GetText("SETTINGS_ADV");
            label2.Text = TextResource.GetText("DESC_USER");
            label3.Text = TextResource.GetText("DESC_PASS");
        }

        private void button1_Click(object sender, EventArgs e)
        {
            SearchClicked?.Invoke(this, EventArgs.Empty);
        }

        private void checkBox1_CheckedChanged(object sender, EventArgs e)
        {
            textBox2.Visible = textBox3.Visible = checkBox1.Checked;
            label2.Visible = label3.Visible = checkBox1.Checked;
        }
    }
}
