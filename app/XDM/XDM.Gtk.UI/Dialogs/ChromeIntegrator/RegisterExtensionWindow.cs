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
    public class RegisterExtensionWindow : Window
    {
        [UI] private Label LblTitle;
        [UI] private Entry TxtExtID;
        [UI] private Button BtnOK;
        [UI] private Button BtnCancel;

        private WindowGroup windowGroup;

        private RegisterExtensionWindow(Builder builder) : base(builder.GetRawOwnedObject("window"))
        {
            builder.Autoconnect(this);
            SetPosition(WindowPosition.CenterAlways);
            SetDefaultSize(400, 200);
            windowGroup = new WindowGroup();
            windowGroup.AddWindow(this);
            GtkHelper.AttachSafeDispose(this);
            this.LoadTexts();
            BtnOK!.Clicked += RegisterExtensionWindow_Clicked;
            BtnCancel!.Clicked += BtnClose_Clicked;
        }

        private void RegisterExtensionWindow_Clicked(object? sender, EventArgs e)
        {
            if (string.IsNullOrEmpty(TxtExtID.Text))
            {
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_FIELD_BLANK"));
                return;
            }
            //ExtensionRegistrationHelper.AddExtension("chrome-extension://" + TxtExtID.Text);
            //NativeMessagingHostConfigurer.InstallNativeMessagingHostForLinux(Browser.Chrome);
            Close();
        }

        private void BtnClose_Clicked(object? sender, EventArgs e)
        {
            this.Close();
        }

        private void LoadTexts()
        {
            this.Title = TextResource.GetText("MSG_REGISTER_EXT");
            this.BtnCancel.Label = TextResource.GetText("ND_CANCEL");
            this.BtnOK.Label = TextResource.GetText("MSG_OK");
            this.LblTitle.Text = TextResource.GetText("MSG_REGISTER_EXT_TEXT");
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

        public static RegisterExtensionWindow CreateFromGladeFile()
        {
            return new RegisterExtensionWindow(GtkHelper.GetBuilder("register-extension"));
        }
    }
}
