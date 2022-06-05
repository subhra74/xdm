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

namespace XDM.GtkUI.Dialogs.DeleteConfirm
{
    public class DeleteConfirmDialog : Dialog
    {
        [UI] private Label TxtLabel;
        [UI] private CheckButton ChkDiskDel;
        [UI] private Button BtnDelete;
        [UI] private Button BtnCancel;

        public bool Result { get; set; } = false;

        private WindowGroup group;

        public string DescriptionText
        {
            set
            {
                TxtLabel.Text = value;
            }
        }

        public bool ShouldDeleteFile => ChkDiskDel.Active;

        private DeleteConfirmDialog(Builder builder, Window parent, WindowGroup group) : base(builder.GetRawOwnedObject("dialog"))
        {
            builder.Autoconnect(this);

           
            Modal = true;
            SetPosition(WindowPosition.CenterAlways);
            TransientFor = parent;
            this.group = group;
            this.group.AddWindow(this);

            GtkHelper.AttachSafeDispose(this);

            BtnDelete.Clicked += BtnDelete_Clicked;
            BtnCancel.Clicked += BtnCancel_Clicked;

            BtnDelete.Label = TextResource.GetText("DESC_DEL");
            BtnCancel.Label = TextResource.GetText("ND_CANCEL");
            ChkDiskDel.Label = TextResource.GetText("LBL_DELETE_FILE");
            TxtLabel.Text = TextResource.GetText("DEL_SEL_TEXT");

            Title = TextResource.GetText("MENU_DELETE_DWN");
            SetDefaultSize(400, 200);
        }

        private void BtnCancel_Clicked(object? sender, EventArgs e)
        {
            Result = false;
            this.group.RemoveWindow(this);
            Visible = false;
        }

        private void BtnDelete_Clicked(object? sender, EventArgs e)
        {
            Result = true;
            this.group.RemoveWindow(this);
            Visible = false;
        }

        public static DeleteConfirmDialog CreateFromGladeFile(Window parent, WindowGroup group)
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "delete-confirm-dialog.glade"));
            return new DeleteConfirmDialog(builder, parent, group);
        }
    }
}
