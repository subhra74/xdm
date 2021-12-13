using System;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Windows.Forms;
using XDM.Core.Lib.Util;
using TraceLog;
using XDM.Core.Lib.Common;
using System.Drawing.Text;
using XDM.WinForm.UI.FormHelper;
using System.IO;
using XDMApp;
using Translations;

namespace XDM.WinForm.UI.SettingsPages
{
    public partial class BrowserMonitoringPage : UserControl, ISettingsPage
    {
        private Font ri64Font, ri16Font;
        private IApp app;
        private PrivateFontCollection fc1;

        public BrowserMonitoringPage(IApp app)
        {
            InitializeComponent();
            this.app = app;
            AutoScrollMinSize = tableLayoutPanel1.Size;
            ri64Font = new Font(GlobalFontCollection.RiFontInstance.Families[0], 32);

            button1.Image = FontImageHelper.FontToBitmap(this, ri64Font, RemixIcon.GetFontIcon("eb8c"), Color.Gray);
            button1.TextImageRelation = TextImageRelation.ImageAboveText;
            button1.Text = "Google Chrome";

            button3.Image = FontImageHelper.FontToBitmap(this, ri64Font, RemixIcon.GetFontIcon("ec7d"), Color.Gray);
            button3.TextImageRelation = TextImageRelation.ImageAboveText;
            button3.Text = "Microsoft Edge";

            button5.Image = FontImageHelper.FontToBitmap(this, ri64Font, RemixIcon.GetFontIcon("ed34"), Color.Gray);
            button5.TextImageRelation = TextImageRelation.ImageAboveText;
            button5.Text = "Mozilla Firefox";

            button6.Image = FontImageHelper.FontToBitmap(this, ri64Font, RemixIcon.GetFontIcon("efb4"), Color.Gray);
            button6.TextImageRelation = TextImageRelation.ImageAboveText;
            button6.Text = "Opera";

            button2.Image = FontImageHelper.FontToBitmap(this, ri64Font, RemixIcon.GetFontIcon("f289"), Color.Gray);
            button2.TextImageRelation = TextImageRelation.ImageAboveText;
            button2.Text = "Vivaldi";

            fc1 = new PrivateFontCollection();
            fc1.AddFontFile(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, @"FontAwesome\brave-logo.ttf"));

            button4.Image = FontImageHelper.FontToBitmap(this, new Font(fc1.Families[0], 32),
                RemixIcon.GetFontIcon("e900"), Color.Gray);
            button4.TextImageRelation = TextImageRelation.ImageAboveText;
            button4.Text = "Brave";

            ri16Font = new Font(GlobalFontCollection.RiFontInstance.Families[0], 12);
            button7.Font = ri16Font;
            button7.Text = RemixIcon.GetFontIcon("ecd5");

            button8.Font = ri16Font;
            button8.Text = RemixIcon.GetFontIcon("ecd5");

            //button5.Font = ri64Font;
            //button5.Text = RemixIcon.GetFontIcon("ed34") + "\nFirefox";

            //button3.Font = ri64Font;
            //button3.Text = RemixIcon.GetFontIcon("ec7d") + "\nEdge";

            LoadTexts();
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            //AllowDarkModeForWindow(this.Handle, 1);
            //SetWindowTheme(this.Handle, "Explorer", null);
            //SetWindowTheme(this.Handle, "DarkMode_Explorer", null);
            //base.OnHandleCreated(e);
        }

        //protected override void OnShown(EventArgs e)
        //{
        //    AllowDarkModeForWindow(this.Handle, 1);
        //    SetWindowTheme(this.Handle, "Explorer", null);
        //    base.OnShown(e);
        //}

        //[DllImport("uxtheme.dll", SetLastError = true, CharSet = CharSet.Unicode, EntryPoint = "#133")]
        //public static extern int AllowDarkModeForWindow(IntPtr hWnd, int allow);

        //[DllImport("uxtheme.dll", SetLastError = true, ExactSpelling = true, CharSet = CharSet.Unicode)]
        //public static extern int SetWindowTheme(IntPtr hWnd, string pszSubAppName, string pszSubIdList);

        private void button1_Click(object sender, EventArgs e)
        {
            try
            {
                Helpers.InstallNativeMessagingHost(NativeHostBrowser.Chrome);
                Helpers.OpenBrowser(app.ChromeExtensionUrl);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error installing native host");
                MessageBox.Show("Error installing native host");
            }
        }

        private void button5_Click(object sender, EventArgs e)
        {
            try
            {
                Helpers.InstallNativeMessagingHost(NativeHostBrowser.Firefox);
                Helpers.OpenBrowser(app.FirefoxExtensionUrl);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error installing native host");
                MessageBox.Show("Error installing native host");
            }
        }

        private void button3_Click(object sender, EventArgs e)
        {
            button1_Click(sender, e);
        }

        private void button7_Click(object sender, EventArgs e)
        {
            Clipboard.SetText(textBox1.Text);
        }

        private void button8_Click(object sender, EventArgs e)
        {
            Clipboard.SetText(textBox2.Text);
        }

        private void button9_Click(object sender, EventArgs e)
        {
            textBox3.Text = string.Join(",", Config.DefaultFileExtensions);
        }

        private void button11_Click(object sender, EventArgs e)
        {
            textBox5.Text = string.Join(",", Config.DefaultVideoExtensions);
        }

        private void button10_Click(object sender, EventArgs e)
        {
            textBox4.Text = string.Join(",", Config.DefaultBlockedHosts);
        }

        public void PopulateUI()
        {
            textBox3.Text = string.Join(",", Config.Instance.FileExtensions);
            textBox5.Text = string.Join(",", Config.Instance.VideoExtensions);
            textBox4.Text = string.Join(",", Config.Instance.BlockedHosts);
            checkBox4.Checked = Config.Instance.FetchServerTimeStamp;
            checkBox2.Checked = Config.Instance.MonitorClipboard;
            numericUpDown1.Value = Config.Instance.MinVideoSize;
        }

        private void button6_Click(object sender, EventArgs e)
        {
            Helpers.InstallNativeMessagingHost(NativeHostBrowser.Chrome);
            Helpers.OpenBrowser(app.ChromeExtensionUrl);
        }

        private void button4_Click(object sender, EventArgs e)
        {
            Helpers.InstallNativeMessagingHost(NativeHostBrowser.Chrome);
            Helpers.OpenBrowser(app.ChromeExtensionUrl);
        }

        private void button2_Click(object sender, EventArgs e)
        {
            Helpers.InstallNativeMessagingHost(NativeHostBrowser.Chrome);
            Helpers.OpenBrowser(app.ChromeExtensionUrl);
        }

        public void UpdateConfig()
        {
            Config.Instance.FileExtensions = textBox3.Text.Split(',').Select(x => x.Trim()).Where(x => x.Length > 0).ToArray();
            Config.Instance.VideoExtensions = textBox5.Text.Split(',').Select(x => x.Trim()).Where(x => x.Length > 0).ToArray();
            Config.Instance.BlockedHosts = textBox4.Text.Split(',').Select(x => x.Trim()).Where(x => x.Length > 0).ToArray();
            Config.Instance.FetchServerTimeStamp = checkBox4.Checked;
            Config.Instance.MonitorClipboard = checkBox2.Checked;
            Config.Instance.MinVideoSize = (int)numericUpDown1.Value;
        }

        private void LoadTexts()
        {
            label1.Text = TextResource.GetText("DESC_MONITORING_1");
            label2.Text = TextResource.GetText("DESC_OTHER_BROWSERS");
            label4.Text = TextResource.GetText("DESC_MOZ");
            label3.Text = TextResource.GetText("DESC_CHROME");
            label5.Text = TextResource.GetText("DESC_FILETYPES");
            button9.Text = TextResource.GetText("DESC_DEF");
            button11.Text = TextResource.GetText("DESC_DEF");
            button10.Text = TextResource.GetText("DESC_DEF");
            label6.Text = TextResource.GetText("DESC_VIDEOTYPES");
            label7.Text = TextResource.GetText("LBL_MIN_VIDEO_SIZE");
            label9.Text = TextResource.GetText("DESC_SITEEXCEPTIONS");
            checkBox2.Text = TextResource.GetText("MENU_CLIP_ADD");
            checkBox4.Text = TextResource.GetText("LBL_GET_TIMESTAMP");
        }
    }
}
