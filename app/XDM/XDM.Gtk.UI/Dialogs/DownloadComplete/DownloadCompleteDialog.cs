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
using XDM.Core.UI;

namespace XDM.GtkUI.Dialogs.DownloadComplete
{
    public class DownloadCompleteDialog : Window, IDownloadCompleteDialog
    {
        public string FileNameText
        {
            get => TxtFileName.Text;
            set => TxtFileName.Text = value;
        }

        public string FolderText
        {
            get => TxtLocation.Text;
            set => TxtLocation.Text = value;
        }
        public event EventHandler<DownloadCompleteDialogEventArgs>? FileOpenClicked;
        public event EventHandler<DownloadCompleteDialogEventArgs>? FolderOpenClicked;
        public event EventHandler? DontShowAgainClickd;

        [UI] private Image ImgFileIcon;
        [UI] private Label TxtFileName;
        [UI] private Label TxtLocation;
        [UI] private Button BtnOpenFolder;
        [UI] private Button BtnOpen;
        [UI] private LinkButton TxtDontShowCompleteDialog;

        private DownloadCompleteDialog(Builder builder) : base(builder.GetRawOwnedObject("window"))
        {
            builder.Autoconnect(this);
            KeepAbove = true;
            Title = TextResource.GetText("CD_TITLE");
            SetPosition(WindowPosition.CenterAlways);

            BtnOpen.Label = TextResource.GetText("CTX_OPEN_FILE");
            BtnOpenFolder.Label = TextResource.GetText("CTX_OPEN_FOLDER");
            TxtDontShowCompleteDialog.Label = TextResource.GetText("MSG_DONT_SHOW_AGAIN");
            TxtFileName.StyleContext.AddClass("large-font");
            TxtFileName.Ellipsize = Pango.EllipsizeMode.End;
            ImgFileIcon.Pixbuf = GtkHelper.LoadSvg("file-download-line", 64);

            BtnOpen.Clicked += BtnOpen_Click;
            BtnOpenFolder.Clicked += BtnOpenFolder_Click;
            TxtDontShowCompleteDialog.ActivateLink += TxtDontShowCompleteDialog_ActivateLink;
            SetDefaultSize(400, 200);

            GtkHelper.AttachSafeDispose(this);
        }

        private void TxtDontShowCompleteDialog_ActivateLink(object o, ActivateLinkArgs args)
        {
            args.RetVal = true;
            DontShowAgainClickd?.Invoke(this, EventArgs.Empty);
            Close();
        }

        private void BtnOpen_Click(object? sender, EventArgs e)
        {
            FileOpenClicked?.Invoke(sender, new DownloadCompleteDialogEventArgs
            {
                Path = IoPath.Combine(TxtLocation.Text, TxtFileName.Text)
            });
            Close();
        }

        public void ShowDownloadCompleteDialog()
        {
            SetDefaultSize(400, 200);
            this.Show();
        }

        private void BtnOpenFolder_Click(object? sender, EventArgs e)
        {
            FolderOpenClicked?.Invoke(sender, new DownloadCompleteDialogEventArgs
            {
                Path = TxtLocation.Text,
                FileName = TxtFileName.Text
            });
            Close();
        }

        public static DownloadCompleteDialog CreateFromGladeFile()
        {
            return new DownloadCompleteDialog(GtkHelper.GetBuilder("download-complete-window"));
        }
    }
}
