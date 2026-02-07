using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Gtk;
using GLib;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Core;
using Translations;
using UI = Gtk.Builder.ObjectAttribute;
using XDM.GtkUI.Utils;

namespace XDM.GtkUI.Dialogs
{
    public class CredentialsDialog : Dialog
    {
        [UI] private Entry TxtUserName;
        [UI] private Entry TxtPassword;
        [UI] private Label TxtMessage;
        [UI] private Label LblUser;
        [UI] private Label LblPassword;
        [UI] private Button BtnOk;
        [UI] private Button BtnCancel;

        private WindowGroup group;

        public AuthenticationInfo? Credentials => new AuthenticationInfo
        {
            UserName = TxtUserName.Text,
            Password = TxtPassword.Text
        };

        public bool Result { get; set; } = false;

        public string PromptText { set => TxtMessage.Text = value; }

        private CredentialsDialog(Builder builder, Window parent, WindowGroup group) : base(builder.GetRawOwnedObject("dialog"))
        {
            builder.Autoconnect(this);
            GtkHelper.ConfigurePasswordField(TxtPassword);

            Title = TextResource.GetText("ND_AUTH");
            SetDefaultSize(400, 200);

            Modal = true;
            SetPosition(WindowPosition.CenterAlways);
            TransientFor = parent;
            this.group = group;
            this.group.AddWindow(this);

            GtkHelper.AttachSafeDispose(this);

            BtnOk.Clicked += BtnOk_Clicked;
            BtnCancel.Clicked += BtnCancel_Clicked;
        }

        private void BtnCancel_Clicked(object? sender, EventArgs e)
        {
            Result = false;
            this.group.RemoveWindow(this);
            Visible = false;
        }

        private void BtnOk_Clicked(object? sender, EventArgs e)
        {
            if (string.IsNullOrEmpty(TxtUserName.Text))
            {
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_NO_USERNAME"));
                return;
            }

            Result = true;
            this.group.RemoveWindow(this);
            Visible = false;
        }

        public static CredentialsDialog CreateFromGladeFile(Window parent, WindowGroup group)
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "credential-dialog.glade"));
            return new CredentialsDialog(builder, parent, group);
        }
    }
}
