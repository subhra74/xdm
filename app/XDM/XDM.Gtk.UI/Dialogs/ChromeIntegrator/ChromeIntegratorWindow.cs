using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Gtk;
using XDM.GtkUI.Utils;
using UI = Gtk.Builder.ObjectAttribute;
using IoPath = System.IO.Path;
using XDM.Core;
using Application = Gtk.Application;
using Translations;
using XDM.Core.Util;
using XDM.Core.BrowserMonitoring;

namespace XDM.GtkUI.Dialogs.ChromeIntegrator
{
    public class ChromeIntegratorWindow : Window
    {
        private int page = 0;

        [UI] private Button BtnNext, BtnBack, BtnHelp, BtnCopyURL, BtnCopy;
        [UI] private Label Page0Lbl1, Page1Lbl1, Page2Lbl1, Page3Lbl1, MsgSuccess, MsgFail, MsgInfo;
        [UI] private Box Page0, Page1, Page2, Page3, Page4;
        [UI] private Image Img1, Img2, Img3, Img4, Img5;
        [UI] private Entry TxtURL, TxtFolder;

        private WindowGroup windowGroup;
        private Browser browser;
        private bool successResult;

        private ChromeIntegratorWindow(Builder builder, Browser browser) : base(builder.GetRawOwnedObject("window"))
        {
            builder.Autoconnect(this);
            this.browser = browser;
            SetPosition(WindowPosition.CenterAlways);
            SetDefaultSize(640, 480);
            windowGroup = new WindowGroup();
            windowGroup.AddWindow(this);
            KeepAbove = true;
            GtkHelper.AttachSafeDispose(this);
            ApplicationContext.ApplicationEvent += ApplicationContext_ApplicationEvent;
            this.Destroyed += ChromeIntegratorWindow_Destroyed;
            BtnNext.Clicked += BtnNext_Clicked;
            BtnBack.Clicked += BtnBack_Clicked;
            BtnHelp.Clicked += BtnHelp_Clicked;
            BtnCopyURL.Clicked += BtnCopyURL_Clicked;
            BtnCopy.Clicked += BtnCopy_Clicked;
            this.LoadTexts();
            TxtURL.Text = "chrome://extensions/";
            TxtFolder.Text = IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "chrome-extension");
            LoadImages();
            RenderPage();
        }

        private void BtnCopy_Clicked(object? sender, EventArgs e)
        {
            var cb = Clipboard.Get(Gdk.Selection.Clipboard);
            if (cb != null)
            {
                cb.Text = TxtFolder.Text;
            }
        }

        private void BtnCopyURL_Clicked(object? sender, EventArgs e)
        {
            var cb = Clipboard.Get(Gdk.Selection.Clipboard);
            if (cb != null)
            {
                cb.Text = TxtURL.Text;
            }
        }

        private void BtnHelp_Clicked(object? sender, EventArgs e)
        {
            PlatformHelper.OpenBrowser(Links.ManualExtensionInstallGuideUrl);
        }

        private void BtnBack_Clicked(object? sender, EventArgs e)
        {
            if (page > 0)
            {
                page--;
            }
            RenderPage();
        }

        private void BtnNext_Clicked(object? sender, EventArgs e)
        {
            if (page < 4)
            {
                page++;
            }
            RenderPage();
        }

        private void LoadTexts()
        {
            this.Title = TextResource.GetText("MSG_CHROME_INT");
            this.BtnNext.Label = TextResource.GetText("MSG_NEXT");
            this.BtnHelp.Label = TextResource.GetText("MSG_HELP");
            this.BtnBack.Label = TextResource.GetText("MSG_BACK");
            this.BtnCopyURL.Label = TextResource.GetText("MSG_COPY");
            this.BtnCopy.Label = TextResource.GetText("MSG_COPY");

            this.Page0Lbl1.Text = String.Format(TextResource.GetText("MSG_COPY_PASTE_EXT_URL"), browser);
            this.Page1Lbl1.Text = TextResource.GetText("MSG_PAGE1_TEXT1");
            this.Page2Lbl1.Text = TextResource.GetText("MSG_PAGE2_TEXT1");
            this.Page3Lbl1.Text = TextResource.GetText("MSG_PAGE3_TEXT1");

            this.MsgSuccess.Text = TextResource.GetText("MSG_EXT_INSTALL_SUCCESS");
            this.MsgFail.Text = TextResource.GetText("MSG_EXT_INSTALL_FAIL");
            this.MsgInfo.Text = TextResource.GetText("MSG_EXT_PIN");

            this.Img5.Visible = this.MsgInfo.Visible = this.MsgSuccess.Visible = false;
        }

        private void LoadImages()
        {
            var img0 = System.IO.Path.Combine(
                    System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "images"),
                    "chrome-addressbar.jpg");
            Img1.File = img0;

            var img1 = System.IO.Path.Combine(
                    System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "images"),
                    $"{browser}.jpg");
            Img2.File = img1;

            var img2 = System.IO.Path.Combine(
                    System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "images"),
                    "load_unpacked.jpg");
            Img3.File = img2;

            var img3 = System.IO.Path.Combine(
                    System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "images"),
                    "extension-folder.jpg");
            Img4.File = img3;

            var img4 = System.IO.Path.Combine(
                    System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "images"),
                    "pin-ext.jpg");
            Img5.File = img4;
        }

        private void ChromeIntegratorWindow_Destroyed(object? sender, EventArgs e)
        {
            ApplicationContext.ApplicationEvent -= ApplicationContext_ApplicationEvent;
        }

        private void ApplicationContext_ApplicationEvent(object? sender, ApplicationEvent e)
        {
            if (e.EventType == "ExtensionRegistered")
            {
                Application.Invoke((_, _) =>
                {
                    successResult = true;
                    MsgFail.Visible = false;
                    MsgSuccess.Visible = true;
                    MsgInfo.Visible = true;
                    Img5.Visible = true;
                    BtnBack.Visible = false;
                });
            }
        }

        public static ChromeIntegratorWindow CreateFromGladeFile(Browser browser)
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "chrome-integration.glade"));
            return new ChromeIntegratorWindow(builder, browser);
        }

        private void RenderPage()
        {
            this.Page0.Visible = page == 0;
            this.Page1.Visible = page == 1;
            this.Page2.Visible = page == 2;
            this.Page3.Visible = page == 3;
            this.Page4.Visible = page == 4;
            this.BtnNext.Visible = page != 4;
            this.BtnBack.Visible = page != 0;
        }
    }
}
