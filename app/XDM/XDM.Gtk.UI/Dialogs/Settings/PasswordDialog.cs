using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Gtk;
using GLib;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Core.Lib.Common;
using Translations;
using UI = Gtk.Builder.ObjectAttribute;
using XDM.GtkUI.Utils;

namespace XDM.GtkUI.Dialogs.Settings
{
    public class PasswordDialog : Dialog
    {
        [UI] private Label Label1, Label2, Label3;
        [UI] private Entry TxtUserName, TxtHost, TxtPassword;
        [UI] private Button BtnOk, BtnCancel;

        public string UserName { get; private set; }
        public string Host { get; private set; }
        public string Password { get; private set; }

        public bool Result { get; set; } = false;

        private WindowGroup group;

        private PasswordDialog(Builder builder, Window parent, WindowGroup group) : base(builder.GetRawOwnedObject("dialog"))
        {
            builder.Autoconnect(this);

            Modal = true;
            SetPosition(WindowPosition.CenterAlways);
            TransientFor = parent;
            this.group = group;
            this.group.AddWindow(this);

            GtkHelper.AttachSafeDispose(this);

            Label1.Text = TextResource.GetText("DESC_HOST");
            Label2.Text = TextResource.GetText("DESC_USER");
            Label3.Text = TextResource.GetText("DESC_PASS");

            BtnOk.Clicked += BtnOk_Clicked;
            BtnCancel.Clicked += BtnCancel_Clicked;

            BtnOk.Label = TextResource.GetText("MSG_OK");
            BtnCancel.Label = TextResource.GetText("ND_CANCEL");

            Title = TextResource.GetText("DESC_PASS");
            SetDefaultSize(400, 300);
        }

        public void SetPassword(PasswordEntry password)
        {
            Host = TxtHost.Text = password.Host;
            UserName = TxtUserName.Text = password.User;
            Password = TxtPassword.Text = password.Password;
        }

        private void BtnOk_Clicked(object? sender, EventArgs e)
        {
            Result = true;
            if (string.IsNullOrEmpty(TxtHost.Text))
            {
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_HOST_NAME_MISSING"));
                return;
            }
            if (string.IsNullOrEmpty(TxtUserName.Text))
            {
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_NO_USERNAME"));
                return;
            }
            this.Host = this.TxtHost.Text;
            this.UserName = this.TxtUserName.Text;
            this.Password = this.TxtPassword.Text;
            Result = true;
            this.group.RemoveWindow(this);
            Dispose();
        }

        private void BtnCancel_Clicked(object? sender, EventArgs e)
        {
            Result = false;
            this.group.RemoveWindow(this);
            Dispose();
        }

        public static PasswordDialog CreateFromGladeFile(Window parent, WindowGroup group)
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "password-dialog.glade"));
            return new PasswordDialog(builder, parent, group);
        }
    }
}
