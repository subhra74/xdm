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

namespace XDM.GtkUI.Dialogs.Settings
{
    public class CategoryEditDialog : Dialog
    {
        [UI] private Label Label1, Label2, Label3;
        [UI] private Entry TxtName, TxtFileTypes, TxtFolder;
        [UI] private Button Browse, BtnOk, BtnCancel;

        public string? DisplayName { get; private set; }
        public string? FileTypes { get; private set; }
        public string? Folder { get; private set; }

        public bool Result { get; set; } = false;

        private WindowGroup group;

        private CategoryEditDialog(Builder builder, Window parent, WindowGroup group) : base(builder.GetRawOwnedObject("dialog"))
        {
            builder.Autoconnect(this);

            Modal = true;
            SetPosition(WindowPosition.CenterAlways);
            TransientFor = parent;
            this.group = group;
            this.group.AddWindow(this);

            GtkHelper.AttachSafeDispose(this);

            Label1.Text = TextResource.GetText("SORT_NAME");
            Label2.Text = TextResource.GetText("SETTINGS_CAT_TYPES");
            Label3.Text = TextResource.GetText("SETTINGS_CAT_FOLDER");

            BtnOk.Clicked += BtnOk_Clicked;
            BtnCancel.Clicked += BtnCancel_Clicked;
            Browse.Clicked += Browse_Clicked;

            BtnOk.Label = TextResource.GetText("MSG_OK");
            BtnCancel.Label = TextResource.GetText("ND_CANCEL");

            Title = TextResource.GetText("MSG_CATEGORY");
            SetDefaultSize(400, 200);
        }

        private void Browse_Clicked(object? sender, EventArgs e)
        {
            var folder = GtkHelper.SelectFolder(this);
            if (!string.IsNullOrEmpty(folder))
            {
                this.TxtFolder.Text = folder;
            }
        }

        public void SetCategory(Category category)
        {
            this.TxtName.Text = category.DisplayName;
            this.TxtFileTypes.Text = string.Join(",", category.FileExtensions.ToArray());
            this.TxtFolder.Text = category.DefaultFolder;
        }

        private void BtnOk_Clicked(object? sender, EventArgs e)
        {
            Result = true;
            if (string.IsNullOrEmpty(TxtName.Text))
            {
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_CAT_NAME_MISSING"));
                return;
            }
            if (string.IsNullOrEmpty(TxtFileTypes.Text))
            {
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_CAT_FILE_TYPES_MISSING"));
                return;
            }
            if (string.IsNullOrEmpty(TxtFolder.Text))
            {
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_CAT_FOLDER_MISSING"));
                return;
            }
            this.DisplayName = this.TxtName.Text;
            this.FileTypes = this.TxtFileTypes.Text;
            this.Folder = this.TxtFolder.Text;
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

        public static CategoryEditDialog CreateFromGladeFile(Window parent, WindowGroup group)
        {
            return new CategoryEditDialog(GtkHelper.GetBuilder("category-edit-dialog"), parent, group);
        }
    }
}
