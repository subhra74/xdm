using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Gtk;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Core.Lib.Common;
using Translations;
using UI = Gtk.Builder.ObjectAttribute;
using XDM.GtkUI.Utils;
using System;
using YDLWrapper;
using XDM.Core.Lib.Util;
using TraceLog;
using XDM.Core.Lib.UI;

namespace XDM.GtkUI.Dialogs.VideoDownloader
{
    public class VideoDownloaderWindow : Window, IVideoDownloadView
    {
        private YDLProcess? ydl;

        [UI] private Box Page1;
        [UI] private Box Page2;
        [UI] private Box Page3;
        [UI] private Label LblUrl;
        [UI] private Label LblUserName;
        [UI] private Label LblPass;
        [UI] private Label LblSaveIn;
        [UI] private Entry TxtUrl;
        [UI] private Entry TxtUserName;
        [UI] private Entry TxtPassword;
        [UI] private Entry TxtSaveIn;
        [UI] private Button BtnGo;
        [UI] private Button BtnCancel;
        [UI] private Button BtnBrowse;
        [UI] private Button BtnDownloadNow;
        [UI] private Button BtnDownloadLater;
        [UI] private Button BtnMore;
        [UI] private CheckButton ChkAuth;
        [UI] private CheckButton ChkSelectAll;
        [UI] private Label LblProgress;
        [UI] private TreeView LvVideoList;
        [UI] private TreeView LvFormats;
        [UI] private ScrolledWindow SwFormats;

        private ListStore videoStore;
        private ListStore formatStore;

        private IApp app;
        private IAppUI AppUI;

        private WindowGroup windowGroup;

        public string DownloadLocation { get => TxtSaveIn.Text; set => TxtSaveIn.Text = value; }
        public string Url { get => TxtUrl.Text; set => TxtUrl.Text = value; }
        public event EventHandler? CancelClicked;
        public event EventHandler? BrowseClicked;
        public event EventHandler? SearchClicked;
        public event EventHandler? WindowClosed;
        public event EventHandler? DownloadClicked;
        public event EventHandler? DownloadLaterClicked;

        public void SwitchToInitialPage()
        {
            Page2.Hide();
            Page3.Hide();
            Page1.Show();
        }
        public void SwitchToProcessingPage()
        {
            Page2.Show();
            Page3.Hide();
            Page1.Hide();
        }
        public void SwitchToFinalPage()
        {
            Page3.Show();
            Page2.Hide();
            Page1.Hide();
        }

        public int SelectedFormat
        {
            get => GtkHelper.GetSelectedIndex(LvFormats);
            set => GtkHelper.SetSelectedIndex(LvFormats, value);
        }

        public IEnumerable<int> SelectedRows => GetSelectedVideoList();

        public int SelectedItemCount => GetSelectedVideoCount();

        public string? SelectFolder()
        {
            return GtkHelper.SelectFolder(this);
        }

        public void SetVideoResultList(IEnumerable<string> videos, IEnumerable<string> formats)
        {
            videoStore.Clear();
            foreach (var video in videos)
            {
                videoStore.AppendValues(true, video);
            }
            formatStore.Clear();
            foreach (var format in formats)
            {
                formatStore.AppendValues(format);
            }
        }

        public void CloseWindow()
        {
            this.Close();
            this.Destroy();
        }

        public void ShowWindow()
        {
            this.Show();
        }

        private VideoDownloaderWindow(Builder builder, IApp app, IAppUI appUi) : base(builder.GetRawOwnedObject("window"))
        {
            builder.Autoconnect(this);
            SetDefaultSize(600, 500);

            windowGroup = new WindowGroup();
            windowGroup.AddWindow(this);

            this.app = app;
            this.AppUI = appUi;

            Title = TextResource.GetText("LBL_VIDEO_DOWNLOAD");
            SetPosition(WindowPosition.Center);

            Page1.ShowAll();
            Page2.Visible = false;
            Page3.Visible = false;

            LblUrl.Text = TextResource.GetText("VID_PASTE_URL");
            ChkAuth.Label = TextResource.GetText("SETTINGS_ADV");
            LblUserName.Text = TextResource.GetText("DESC_USER");
            LblPass.Text = TextResource.GetText("DESC_PASS");
            LblSaveIn.Text = TextResource.GetText("LBL_SAVE_IN");
            LblProgress.Text = TextResource.GetText("STAT_WAITING");
            BtnCancel.Label = TextResource.GetText("ND_CANCEL");

            ChkSelectAll.Label = TextResource.GetText("VID_CHK");

            BtnDownloadNow.Label = TextResource.GetText("ND_DOWNLOAD_NOW");
            BtnDownloadLater.Label = TextResource.GetText("ND_DOWNLOAD_LATER");
            BtnMore.Label = TextResource.GetText("ND_MORE");

            TxtUserName.Visible = TxtPassword.Visible = LblUserName.Visible = LblPass.Visible = false;

            ChkAuth.Toggled += (_, _) =>
            {
                TxtUserName.Visible = TxtPassword.Visible = LblUserName.Visible = LblPass.Visible = ChkAuth.Active;
            };

            BtnGo.Clicked += BtnGo_Clicked;
            BtnCancel.Clicked += BtnCancel_Clicked;

            videoStore = new ListStore(typeof(bool), typeof(string), typeof(YDLVideoEntry));
            formatStore = new ListStore(typeof(string), typeof(int));

            LvVideoList.Model = videoStore;
            LvFormats.Model = formatStore;

            var fileNameColumn = new TreeViewColumn
            {
                Resizable = true,
                Reorderable = false,
                Title = "Name",
                Sizing = TreeViewColumnSizing.Autosize
            };

            var checkboxRenderer = new CellRendererToggle { };
            checkboxRenderer.Toggled += CheckboxRenderer_Toggled;
            fileNameColumn.PackStart(checkboxRenderer, false);
            fileNameColumn.SetAttributes(checkboxRenderer, "active", 0);

            var fileNameRendererText = new CellRendererText();
            fileNameColumn.PackStart(fileNameRendererText, false);
            fileNameColumn.SetAttributes(fileNameRendererText, "text", 1);
            LvVideoList.AppendColumn(fileNameColumn);

            var formatColumn = new TreeViewColumn
            {
                Resizable = true,
                Reorderable = false,
                Title = "Formats",
                Sizing = TreeViewColumnSizing.Autosize
            };

            var formatRendererText = new CellRendererText();
            formatColumn.PackStart(formatRendererText, false);
            formatColumn.SetAttributes(formatRendererText, "text", 0);
            LvFormats.AppendColumn(formatColumn);

            SwFormats.SetSizeRequest(100, 100);

            DeleteEvent += VideoDownloaderWindow_DeleteEvent;

            BtnBrowse.Clicked += BtnBrowse_Clicked;
            BtnDownloadNow.Clicked += BtnDownloadNow_Clicked;

            TxtSaveIn.Text = Helpers.GetVideoDownloadFolder();
        }

        private void BtnGo_Clicked(object? sender, EventArgs e)
        {
            SearchClicked?.Invoke(this, EventArgs.Empty);
        }

        private void BtnDownloadNow_Clicked(object? sender, EventArgs e)
        {
            DownloadClicked?.Invoke(this, EventArgs.Empty);
        }

        private void BtnBrowse_Clicked(object? sender, EventArgs e)
        {
            BrowseClicked?.Invoke(this, EventArgs.Empty);
        }

        private void VideoDownloaderWindow_DeleteEvent(object o, DeleteEventArgs args)
        {
            WindowClosed?.Invoke(this, EventArgs.Empty);
        }

        private void BtnCancel_Clicked(object? sender, EventArgs e)
        {
            CancelClicked?.Invoke(this, EventArgs.Empty);
        }

        private void CheckboxRenderer_Toggled(object o, ToggledArgs args)
        {
            TreeIter iter;
            if (videoStore.GetIter(out iter, new TreePath(args.Path)))
                videoStore.SetValue(iter, 0, !(bool)videoStore.GetValue(iter, 0));
        }

        //public void SetVideoResultList(List<YDLVideoEntry> items)
        //{
        //    if (items == null) return;
        //    var formatSet = new HashSet<int>();

        //    videoStore.Clear();
        //    foreach (var item in items)
        //    {
        //        videoStore.AppendValues(true, item.Title, item);
        //    }

        //    foreach (var item in items)
        //    {
        //        if (item.Formats != null)
        //        {
        //            item.Formats.ForEach(item =>
        //            {
        //                if (!string.IsNullOrEmpty(item.Height))
        //                {
        //                    if (Int32.TryParse(item.Height, out int height))
        //                    {
        //                        formatSet.Add(height);
        //                    }
        //                }
        //            });
        //        }
        //    }
        //    var formatsList = new List<int>(formatSet);
        //    formatsList.Sort();
        //    formatsList.Reverse();

        //    formatStore.Clear();
        //    foreach (var format in formatsList)
        //    {
        //        formatStore.AppendValues($"{format}p", format);
        //    }

        //    if (formatsList.Count > 0)
        //    {
        //        GtkHelper.SetSelectedIndex(LvFormats, 0);
        //    }

        //    //this.videoQualities = formatsList;
        //    //LbQuality.ItemsSource = this.videoQualities.Select(n => $"{n}p");
        //    //if (this.videoQualities.Count > 0)
        //    //{
        //    //    LbQuality.SelectedIndex = 0;
        //    //}
        //}

        //private void DownloadSelectedItems(bool startImmediately, string? queueId)
        //{
        //    if (string.IsNullOrEmpty(TxtSaveIn.Text))
        //    {
        //        AppUI!.ShowMessageBox(ParentWindow, TextResource.GetText("MSG_CAT_FOLDER_MISSING"));
        //        return;
        //    }
        //    if (this.GetSelectedVideoCount() == 0)
        //    {
        //        AppUI!.ShowMessageBox(ParentWindow, TextResource.GetText("BAT_SELECT_ITEMS"));
        //        return;
        //    }
        //    var quality = -1;
        //    if (GtkHelper.GetSelectedIndex(LvFormats) >= 0)
        //    {
        //        quality = this.GetSelectedFormat();
        //    }

        //    foreach (var item in this.GetSelectedVideoList())
        //    {
        //        var fmt = FindMatchingFormatByQuality(item, quality);
        //        if (fmt.HasValue)
        //        {
        //            AddDownload(fmt.Value, startImmediately, queueId);
        //        }
        //    }
        //    this.Close();
        //}

        private int GetSelectedVideoCount()
        {
            if (!videoStore.GetIterFirst(out TreeIter iter))
            {
                return 0;
            }
            var count = 0;
            do
            {
                if ((bool)videoStore.GetValue(iter, 0))
                {
                    count++;
                }
            }
            while (videoStore.IterNext(ref iter));
            return count;
        }

        private List<int> GetSelectedVideoList()
        {
            var list = new List<int>();
            if (!videoStore.GetIterFirst(out TreeIter iter))
            {
                return list;
            }
            var count = 0;
            do
            {
                if ((bool)videoStore.GetValue(iter, 0))
                {
                    list.Add(count);
                }
                count++;
            }
            while (videoStore.IterNext(ref iter));
            return list;
        }

        //private List<YDLVideoEntry> GetSelectedVideoList()
        //{
        //    var list = new List<YDLVideoEntry>();
        //    if (!videoStore.GetIterFirst(out TreeIter iter))
        //    {
        //        return list;
        //    }
        //    do
        //    {
        //        if ((bool)videoStore.GetValue(iter, 0))
        //        {
        //            var entry = (YDLVideoEntry)videoStore.GetValue(iter, 2);
        //            list.Add(entry);
        //        }
        //    }
        //    while (videoStore.IterNext(ref iter));
        //    return list;
        //}

        private int GetSelectedFormat()
        {
            var paths = LvFormats.Selection.GetSelectedRows();
            if (paths != null && paths.Length > 0)
            {
                if (formatStore.GetIter(out TreeIter iter, paths[0]))
                {
                    return (int)formatStore.GetValue(iter, 1);
                }
            }
            return -1;
        }

        public static VideoDownloaderWindow CreateFromGladeFile(IApp app, IAppUI appUi)
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "video-downloader-window.glade"));
            return new VideoDownloaderWindow(builder, app, appUi);
        }
    }
}
