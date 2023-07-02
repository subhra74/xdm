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
using XDM.Core.Downloader;

namespace XDM.GtkUI.Dialogs.Updater
{
    public class UpdaterWindow : Window, IUpdaterUI
    {
        [UI] private Label TxtHeading;
        [UI] private ProgressBar Prg;
        [UI] private Button BtnCancel;
        private bool active = false;

        private UpdaterWindow(Builder builder) : base(builder.GetRawOwnedObject("window"))
        {
            builder.Autoconnect(this);
            Title = TextResource.GetText("OPT_UPDATE_FFMPEG");
            SetPosition(WindowPosition.CenterAlways);

            BtnCancel.Label = TextResource.GetText("ND_CANCEL");
            TxtHeading.Text = TextResource.GetText("STAT_DOWNLOADING");
            SetDefaultSize(500, 200);

            GtkHelper.AttachSafeDispose(this);

            Realized += UpdaterWindow_Realized;
            BtnCancel.Clicked += BtnCancel_Clicked;
            DeleteEvent += UpdaterWindow_DeleteEvent;
            BtnCancel.Clicked += BtnCancel_Clicked;
        }

        private void UpdaterWindow_DeleteEvent(object o, DeleteEventArgs args)
        {
            if (active)
            {
                Cancelled?.Invoke(this, EventArgs.Empty);
            }
        }

        private void BtnCancel_Clicked(object? sender, EventArgs e)
        {
            Cancelled?.Invoke(sender, e);
        }

        private void CloseWindow()
        {
            Close();
            Dispose();
        }

        public void DownloadCancelled(object? sender, EventArgs e)
        {
            active = false;
            Application.Invoke((_, _) => CloseWindow());
        }

        private void UpdaterWindow_Realized(object? sender, EventArgs e)
        {
            Load?.Invoke(this, EventArgs.Empty);
        }

        public string Label
        {
            get => TxtHeading.Text;
            set => Application.Invoke((_, _) => TxtHeading.Text = value);
        }

        public bool Inderminate { get; set; }

        public event EventHandler? Cancelled;
        public event EventHandler? Finished;
        public event EventHandler? Load;

        public static UpdaterWindow CreateFromGladeFile()
        {
            return new UpdaterWindow(GtkHelper.GetBuilder("updater-window"));
        }

        public void DownloadFailed(object? sender, DownloadFailedEventArgs e)
        {
            active = false;
            Application.Invoke((_, _) =>
            {
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_FAILED"));
                CloseWindow();
            });
        }

        public void DownloadFinished(object? sender, EventArgs e)
        {
            active = false;
            Application.Invoke((_, _) =>
            {
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_UPDATED"));
                CloseWindow();
            });
            this.Finished?.Invoke(sender, e);
        }

        public void DownloadProgressChanged(object? sender, ProgressResultEventArgs e)
        {
            Application.Invoke((_, _) => Prg.Fraction = e.Progress / 100.0d);
        }

        public void DownloadStarted(object? sender, EventArgs e)
        {
            active = true;
        }

        public void ShowNoUpdateMessage()
        {
            active = false;
            Application.Invoke((_, _) =>
            {
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_NO_UPDATE"));
                CloseWindow();
            });
            this.Finished?.Invoke(this, EventArgs.Empty);
        }
    }
}
