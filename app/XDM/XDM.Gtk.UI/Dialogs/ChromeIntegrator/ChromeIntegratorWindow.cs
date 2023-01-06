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

namespace XDM.GtkUI.Dialogs.ChromeIntegrator
{
    public class ChromeIntegratorWindow : Window
    {
        private int page = 0;
        [UI] private Button BtnNext, BtnBack, BtnHelp, BtnCopyURL, BtnCopy;
        [UI] private Label Page0Lbl1, Page1Lbl1, Page2Lbl1, Page3Lbl1, MsgSuccess, MsgFail, MsgInfo;
        [UI] private Box Page0, Page1, Page2, Page3, Page4;

        private WindowGroup windowGroup;

        private ChromeIntegratorWindow(Builder builder) : base(builder.GetRawOwnedObject("window"))
        {
            builder.Autoconnect(this);
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
            this.LoadTexts();
            //BtnHelp!.Clicked += BtnHelp_Clicked;
            //BtnClose!.Clicked += BtnClose_Clicked;
            //TxtGuide!.WrapMode = WrapMode.Word;

            //Drag.SourceSet(Label1, Gdk.ModifierType.Button1Mask,
            //    new TargetEntry[] { new TargetEntry("text/uri-list", (TargetFlags)0, 1) }, Gdk.DragAction.Copy);
            //Label1!.DragDataGet += Label1_DragDataGet;
            RenderPage();
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

        private void Label1_DragDataGet(object o, DragDataGetArgs args)
        {
            if (args.Info == 1)
            {
                args.SelectionData.SetUris(new string[] { "file://var" });
            }
        }

        private void BtnClose_Clicked(object? sender, EventArgs e)
        {
            this.Close();
        }

        private void LoadTexts()
        {
            this.Title = TextResource.GetText("MSG_CHROME_INT");
            this.BtnNext.Label = TextResource.GetText("MSG_NEXT");
            this.BtnHelp.Label = TextResource.GetText("MSG_HELP");
            this.BtnBack.Label = TextResource.GetText("MSG_BACK");
            this.BtnCopyURL.Label = TextResource.GetText("MSG_COPY");
            this.BtnCopy.Label = TextResource.GetText("MSG_COPY");

            this.Page0Lbl1.Text = TextResource.GetText("MSG_COPY_PASTE_EXT_URL");
            this.Page1Lbl1.Text = TextResource.GetText("MSG_PAGE1_TEXT1");
            this.Page2Lbl1.Text = TextResource.GetText("MSG_PAGE2_TEXT1");
            this.Page3Lbl1.Text = TextResource.GetText("MSG_PAGE3_TEXT1");

            this.MsgSuccess.Text = TextResource.GetText("MSG_EXT_INSTALL_SUCCESS");
            this.MsgFail.Text = TextResource.GetText("MSG_EXT_INSTALL_FAIL");
            this.MsgInfo.Text = TextResource.GetText("MSG_EXT_PIN");

            //var buffer = this.TxtGuide.Buffer;
            //buffer.Text = $"{TextResource.GetText("MSG_LINUX_EXT1")} Chrome" +
            //    "\n" + TextResource.GetText("MSG_LINUX_EXT2") +
            //    "\n" + TextResource.GetText("MSG_LINUX_EXT3") +
            //    "\n" + TextResource.GetText("MSG_LINUX_EXT4") +
            //    "\n" + TextResource.GetText("MSG_LINUX_EXT5") + System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "chrome-extension") +
            //    "\n" + TextResource.GetText("MSG_LINUX_EXT6") +
            //    "\n" + TextResource.GetText("MSG_LINUX_EXT7") + Links.ManualExtensionInstallGuideUrl;
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
                    GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_PAGE3_TEXT3") + "\r\n" + TextResource.GetText("MSG_PAGE3_TEXT4"));
                    this.Close();
                });
            }
        }

        public static ChromeIntegratorWindow CreateFromGladeFile()
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "chrome-integration.glade"));
            return new ChromeIntegratorWindow(builder);
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
