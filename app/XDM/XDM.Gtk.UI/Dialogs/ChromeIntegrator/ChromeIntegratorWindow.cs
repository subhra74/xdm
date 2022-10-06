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
        [UI] private TextView TxtGuide;
        [UI] private Button BtnClose;
        [UI] private Button BtnHelp;


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
            this.LoadTexts();
            BtnHelp!.Clicked += BtnHelp_Clicked;
            BtnClose!.Clicked += BtnClose_Clicked;
            TxtGuide!.WrapMode = WrapMode.Word;
        }

        private void BtnClose_Clicked(object? sender, EventArgs e)
        {
            this.Close();
        }

        private void BtnHelp_Clicked(object? sender, EventArgs e)
        {
            PlatformHelper.OpenBrowser(Links.ManualExtensionInstallGuideUrl);
        }

        private void LoadTexts()
        {
            this.Title = TextResource.GetText("MSG_CHROME_INT");
            this.BtnClose.Label = TextResource.GetText("MSG_CLOSE");
            this.BtnHelp.Label = TextResource.GetText("MSG_HELP");
            var buffer = this.TxtGuide.Buffer;
            buffer.Text = TextResource.GetText("MSG_LINUX_EXT1") +
                "\n" + TextResource.GetText("MSG_LINUX_EXT2") +
                "\n" + TextResource.GetText("MSG_LINUX_EXT3") +
                "\n" + TextResource.GetText("MSG_LINUX_EXT4") +
                "\n" + TextResource.GetText("MSG_LINUX_EXT5") +
                "\n" + TextResource.GetText("MSG_LINUX_EXT6");
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
    }
}
