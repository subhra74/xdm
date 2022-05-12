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

namespace XDM.GtkUI.Dialogs.VideoDownloader
{
    public class VideoDownloaderWindow : Window
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
        private IAppUI appUi;

        private VideoDownloaderWindow(Builder builder, IApp app, IAppUI appUi) : base(builder.GetRawOwnedObject("window"))
        {
            builder.Autoconnect(this);
            SetDefaultSize(600, 500);

            this.app = app;
            this.appUi = appUi;

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

            videoStore = new ListStore(typeof(bool), typeof(string));
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
        }

        private void CheckboxRenderer_Toggled(object o, ToggledArgs args)
        {
            TreeIter iter;
            if (videoStore.GetIter(out iter, new TreePath(args.Path)))
                videoStore.SetValue(iter, 0, !(bool)videoStore.GetValue(iter, 0));
        }

        private void BtnCancel_Clicked(object? sender, EventArgs e)
        {
            try
            {
                if (ydl != null)
                {
                    ydl.Cancel();
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error cancelling ydl");
            }
            Page2.Hide();
            Page3.Hide();
            Page1.Show();
        }

        private void BtnGo_Clicked(object? sender, EventArgs e)
        {
            if (Helpers.IsUriValid(TxtUrl.Text))
            {
                Page1.Hide();
                Page2.ShowAll();
                ProcessVideo(TxtUrl.Text, result => Application.Invoke((_, _) =>
                {
                    Page2.Hide();
                    if (result == null)
                    {
                        Page3.Hide();
                        Page1.Show();
                    }
                    else
                    {
                        Page3.ShowAll();
                        SetVideoResultList(result);
                    }
                }));
            }
            else
            {
                appUi.ShowMessageBox(this, TextResource.GetText("MSG_INVALID_URL"));
            }
        }

        public void SetVideoResultList(List<YDLVideoEntry> items)
        {
            if (items == null) return;
            var formatSet = new HashSet<int>();

            videoStore.Clear();
            foreach (var item in items)
            {
                videoStore.AppendValues(true, item.Title);
            }

            foreach (var item in items)
            {
                if (item.Formats != null)
                {
                    item.Formats.ForEach(item =>
                    {
                        if (!string.IsNullOrEmpty(item.Height))
                        {
                            if (Int32.TryParse(item.Height, out int height))
                            {
                                formatSet.Add(height);
                            }
                        }
                    });
                }
            }
            var formatsList = new List<int>(formatSet);
            formatsList.Sort();
            formatsList.Reverse();

            formatStore.Clear();
            foreach (var format in formatsList)
            {
                formatStore.AppendValues($"{format}p", format);
            }

            if (formatsList.Count > 0)
            {
                GtkHelper.SetSelectedIndex(LvFormats, 0);
            }

            //this.videoQualities = formatsList;
            //LbQuality.ItemsSource = this.videoQualities.Select(n => $"{n}p");
            //if (this.videoQualities.Count > 0)
            //{
            //    LbQuality.SelectedIndex = 0;
            //}
        }

        private void ProcessVideo(string url, Action<List<YDLVideoEntry>?> callback)
        {
            ydl = new YDLProcess
            {
                Uri = new Uri(url)
            };
            new System.Threading.Thread(() =>
            {
                try
                {
                    ydl.Start();
                    callback.Invoke(YDLOutputParser.Parse(ydl.JsonOutputFile));
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, "Error while running youtube-dl");
                    callback.Invoke(null);
                }
            }).Start();
        }

        public static VideoDownloaderWindow CreateFromGladeFile(IApp app, IAppUI appUi)
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "video-downloader-window.glade"));
            return new VideoDownloaderWindow(builder, app, appUi);
        }
    }
}
