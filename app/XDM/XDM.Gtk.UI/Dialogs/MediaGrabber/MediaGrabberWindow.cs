using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Gtk;
using Translations;
using XDM.GtkUI.Utils;
using UI = Gtk.Builder.ObjectAttribute;
using IoPath = System.IO.Path;
using XDM.Core;
using XDM.Core.BrowserMonitoring;

namespace XDM.GtkUI.Dialogs.MediaGrabber
{
    public class MediaGrabberWindow : Window
    {
#pragma warning disable IDE0044 // Add readonly modifier
        [UI] private CheckButton ChkTopMost;
        [UI] private TreeView LvVideos;
        [UI] private Button BtnClear;
        [UI] private Button BtnDownload;
        [UI] private Button BtnClose;
        [UI] private LinkButton HowToLink;
#pragma warning restore IDE0044 // Add readonly modifier
        private ListStore store;

        private MediaGrabberWindow(Builder builder) : base(builder.GetRawOwnedObject("window"))
        {
            builder.Autoconnect(this);
            SetDefaultSize(640, 400);

            Title = TextResource.GetText("MSG_MEDIA_CAPTURE");
            SetPosition(WindowPosition.CenterAlways);

            GtkHelper.AttachSafeDispose(this);

            store = new ListStore(typeof(string), typeof(string), typeof(string));
            LvVideos!.Model = store;

            var nameColumn = new TreeViewColumn
            {
                Resizable = true,
                Reorderable = false,
                Title = TextResource.GetText("SORT_NAME"),
                Sizing = TreeViewColumnSizing.Fixed,
                FixedWidth = 400
            };

            var infoColumn = new TreeViewColumn
            {
                Resizable = true,
                Reorderable = false,
                Title = TextResource.GetText("MSG_QUALITY"),
                Sizing = TreeViewColumnSizing.Fixed,
                FixedWidth = 150
            };

            var nameRenderer = new CellRendererText { };
            nameColumn.PackStart(nameRenderer, true);
            nameColumn.SetAttributes(nameRenderer, "text", 0);
            LvVideos.AppendColumn(nameColumn);

            var infoRenderer = new CellRendererText();
            infoColumn.PackStart(infoRenderer, false);
            infoColumn.SetAttributes(infoRenderer, "text", 1);
            LvVideos.AppendColumn(infoColumn);

            LoadTexts();

            this.Destroyed += MediaGrabberWindow_Destroyed;
            ApplicationContext.VideoTracker.MediaAdded += VideoTracker_MediaAdded;
            ApplicationContext.VideoTracker.MediaUpdated += VideoTracker_MediaUpdated;

            BtnClear!.Clicked += BtnClear_Clicked;
            BtnDownload!.Clicked += BtnDownload_Clicked;
            BtnClose!.Clicked += BtnClose_Clicked;
            ChkTopMost!.Toggled += ChkTopMost_Toggled;

            var list = ApplicationContext.VideoTracker.GetVideoList();
            if (list.Count > 0)
            {
                foreach (var mi in list)
                {
                    store.AppendValues(mi.Name, mi.Description, mi.ID);
                }
            }
            KeepAbove = true;
            ChkTopMost.Active = true;
        }

        private void ChkTopMost_Toggled(object? sender, EventArgs e)
        {
            KeepAbove = ChkTopMost.Active;
        }

        private void BtnClose_Clicked(object? sender, EventArgs e)
        {
            Dispose();
        }

        private void BtnDownload_Clicked(object? sender, EventArgs e)
        {
            var id = GtkHelper.GetSelectedValue<string>(this.LvVideos, 2);
            if (!string.IsNullOrEmpty(id))
            {
                ApplicationContext.VideoTracker.AddVideoDownload(id);
            }
        }

        private void BtnClear_Clicked(object? sender, EventArgs e)
        {
            ApplicationContext.VideoTracker.ClearVideoList();
            this.store.Clear();
        }

        private void VideoTracker_MediaUpdated(object? sender, Core.BrowserMonitoring.MediaInfoEventArgs e)
        {
            Gtk.Application.Invoke((_, _) => { UpdateMedia(e.MediaInfo); });
        }

        private void VideoTracker_MediaAdded(object? sender, Core.BrowserMonitoring.MediaInfoEventArgs e)
        {
            Gtk.Application.Invoke((_, _) => { AddMedia(e.MediaInfo); });
        }

        private void AddMedia(MediaInfo info)
        {
            store.AppendValues(info.Name, info.Description, info.ID);
        }

        private void UpdateMedia(MediaInfo info)
        {
            if (!store.GetIterFirst(out TreeIter iter))
            {
                return;
            }
            do
            {
                var id = (string)store.GetValue(iter, 2);
                if (id == info.ID)
                {
                    store.SetValue(iter, 0, info.Name);
                    break;
                }
            }
            while (store.IterNext(ref iter));
        }

        private void MediaGrabberWindow_Destroyed(object? sender, EventArgs e)
        {
            ApplicationContext.VideoTracker.MediaAdded -= VideoTracker_MediaAdded;
            ApplicationContext.VideoTracker.MediaUpdated -= VideoTracker_MediaUpdated;
        }

        private void LoadTexts()
        {
            ChkTopMost.Label = TextResource.GetText("MSG_ALWAYS_ON_TOP");
            HowToLink.Label = TextResource.GetText("MSG_HOW_TO_USE_MG");
            BtnClear.Label = TextResource.GetText("MSG_CLEAR");
            BtnClose.Label = TextResource.GetText("MSG_CLOSE");
            BtnDownload.Label = TextResource.GetText("MSG_DOWNLOAD");
        }

        public static MediaGrabberWindow CreateFromGladeFile()
        {
            return new MediaGrabberWindow(GtkHelper.GetBuilder("media-grabber"));
        }
    }
}
