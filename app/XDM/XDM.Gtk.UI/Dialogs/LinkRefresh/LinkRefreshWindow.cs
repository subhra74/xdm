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

namespace XDM.GtkUI.Dialogs.LinkRefresh
{
    internal class LinkRefreshWindow : Window, IRefreshLinkDialogSkeleton
    {
        [UI] private Label LblText;
        [UI] private Button BtnStop;

        private WindowGroup group;

        public event EventHandler? WatchingStopped;

        public void LinkReceived()
        {
            Application.Invoke((_, _) =>
            {
                try
                {
                    GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_REF_LINK_MSG"));
                    CloseAndDispose();
                }
                catch { }
            });
        }

        public void ShowWindow()
        {
            this.ShowAll();
        }

        private LinkRefreshWindow(Builder builder) : base(builder.GetRawOwnedObject("window"))
        {
            builder.Autoconnect(this);
            SetDefaultSize(400, 200);
            SetPosition(WindowPosition.CenterAlways);
            this.group = new WindowGroup();
            this.group.AddWindow(this);

            GtkHelper.AttachSafeDispose(this);

            BtnStop.Label = TextResource.GetText("BTN_STOP_PROCESSING");
            LblText.Text = TextResource.GetText("REF_WAITING_FOR_LINK");

            Title = TextResource.GetText("MENU_REFRESH_LINK");
            DeleteEvent += LinkRefreshWindow_DeleteEvent;
            BtnStop.Clicked += BtnStop_Clicked;
        }

        private void CloseAndDispose()
        {
            this.Close();
            this.Destroy();
            this.Dispose();
        }

        private void BtnStop_Clicked(object? sender, EventArgs e)
        {
            CloseAndDispose();
        }

        private void LinkRefreshWindow_DeleteEvent(object o, DeleteEventArgs args)
        {
            WatchingStopped?.Invoke(this, EventArgs.Empty);
        }

        public static LinkRefreshWindow CreateFromGladeFile()
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "link-refresh-window.glade"));
            return new LinkRefreshWindow(builder);
        }
    }
}
