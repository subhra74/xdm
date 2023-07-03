using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Gtk;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Core;
using Translations;
using UI = Gtk.Builder.ObjectAttribute;
using XDM.GtkUI.Utils;
using System;
using YDLWrapper;
using XDM.Core.Util;
using TraceLog;
using XDM.Core.UI;
using XDM.GtkUI.Dialogs.AdvancedDownload;
using XDM.Core.Downloader;

namespace XDM.GtkUI.Dialogs.DownloadSelection
{
    internal class DownloadSelectionWindow : Window, IDownloadSelectionView
    {
        public string DownloadLocation { get => TxtSaveIn.Text; set => TxtSaveIn.Text = value; }
        public AuthenticationInfo? Authentication { get => authentication; set => authentication = value; }
        public ProxyInfo? Proxy { get => proxy; set => proxy = value; }
        public int SpeedLimit { get => speedLimit; set => speedLimit = value; }
        public bool EnableSpeedLimit { get => enableSpeedLimit; set => enableSpeedLimit = value; }
        public int SelectedRowCount => this.GetSelectedRowCount();
        public IEnumerable<IDownloadEntryWrapper> SelectedItems => this.GetSelectedRows();

        public event EventHandler? BrowseClicked;
        public event EventHandler? DownloadClicked;
        public event EventHandler? QueueSchedulerClicked;
        public event EventHandler<DownloadLaterEventArgs>? DownloadLaterClicked;

        public void CloseWindow()
        {
            this.Close();
            this.Destroy();
            this.Dispose();
        }

        public string? SelectFolder()
        {
            return GtkHelper.SelectFolder(this);
        }

        public void SetData(FileNameFetchMode mode, IEnumerable<IRequestData> downloads, Func<IRequestData, IDownloadEntryWrapper, bool> populateEntryWrapper)
        {
            foreach (IDownloadEntryWrapper entry in downloads.Select(o =>
             {
                 var ent = new DownloadEntryWrapper();
                 populateEntryWrapper.Invoke(o, ent);
                 return ent;
             }))
            {
                store.AppendValues(true, entry.Name, entry);
            }
        }

        public void ShowWindow()
        {
            ShowAll();
        }

        private void PrepareMenu()
        {
            dontAddToQueueMenuItem = new Gtk.MenuItem(TextResource.GetText("LBL_QUEUE_OPT3"));
            queueAndSchedulerMenuItem = new Gtk.MenuItem(TextResource.GetText("DESC_Q_TITLE"));

            dontAddToQueueMenuItem.Activated += DontAddToQueueMenuItem_Click;
            queueAndSchedulerMenuItem.Activated += QueueAndSchedulerMenuItem_Click;

            DownloadLaterMenuHelper.PopulateMenuAndAttachEvents(
                args => DownloadLaterClicked?.Invoke(this, args),
                menu1,
                dontAddToQueueMenuItem,
                queueAndSchedulerMenuItem,
                this);
        }

        private void DontAddToQueueMenuItem_Click(object? sender, EventArgs e)
        {
            this.DownloadLaterClicked?.Invoke(this, new DownloadLaterEventArgs(string.Empty));
        }

        private void QueueAndSchedulerMenuItem_Click(object? sender, EventArgs e)
        {
            this.QueueSchedulerClicked?.Invoke(this, EventArgs.Empty);
        }

        private Gtk.MenuItem dontAddToQueueMenuItem;
        private Gtk.MenuItem queueAndSchedulerMenuItem;

        private AuthenticationInfo? authentication;
        private ProxyInfo? proxy = Config.Instance.Proxy;
        private int speedLimit = Config.Instance.DefaltDownloadSpeed;
        private bool enableSpeedLimit = Config.Instance.EnableSpeedLimit;

        [UI] private Label LblSaveIn;
        [UI] private CheckButton ChkSelectAll;
        [UI] private TreeView LbDownloadList;
        [UI] private Entry TxtSaveIn;
        [UI] private Button BtnBrowse;
        [UI] private Gtk.Menu menu1;
        [UI] private Button BtnDownloadNow;
        [UI] private MenuButton BtnDownloadLater;
        [UI] private Button BtnMore;

        private WindowGroup windowGroup;
        private ListStore store;

        private int GetSelectedRowCount()
        {
            return GtkHelper.GetListStoreValues<IDownloadEntryWrapper>(store, 2).Where(x => x.IsSelected).Count();
        }

        private IEnumerable<IDownloadEntryWrapper> GetSelectedRows()
        {
            return GtkHelper.GetListStoreValues<IDownloadEntryWrapper>(store, 2).Where(x => x.IsSelected);
        }

        private DownloadSelectionWindow(Builder builder) : base(builder.GetRawOwnedObject("window"))
        {
            builder.Autoconnect(this);
            SetDefaultSize(600, 500);

            windowGroup = new WindowGroup();
            windowGroup.AddWindow(this);

            Title = TextResource.GetText("BAT_SELECT_ITEMS");
            SetPosition(WindowPosition.CenterAlways);

            PrepareMenu();
            GtkHelper.AttachSafeDispose(this);

            store = new ListStore(typeof(bool), typeof(string), typeof(IDownloadEntryWrapper));
            LbDownloadList.Model = store;
            LbDownloadList.HeadersVisible = false;

            var fileNameColumn = new TreeViewColumn
            {
                Resizable = false,
                Reorderable = false,
                Title = TextResource.GetText("SORT_NAME"),
                Sizing = TreeViewColumnSizing.Autosize,
                Expand = true
            };

            var checkboxRenderer = new CellRendererToggle { };
            checkboxRenderer.Toggled += CheckboxRenderer_Toggled;
            fileNameColumn.PackStart(checkboxRenderer, false);
            fileNameColumn.SetAttributes(checkboxRenderer, "active", 0);

            var fileNameRendererText = new CellRendererText();
            fileNameColumn.PackStart(fileNameRendererText, false);
            fileNameColumn.SetAttributes(fileNameRendererText, "text", 1);
            LbDownloadList.AppendColumn(fileNameColumn);

            ChkSelectAll.Toggled += ChkSelectAll_Toggled;
            BtnDownloadNow.Clicked += BtnDownloadNow_Clicked;
            BtnMore.Clicked += BtnMore_Clicked;
            BtnBrowse.Clicked += BtnBrowse_Clicked;

            LoadTexts();
        }

        private void BtnBrowse_Clicked(object? sender, EventArgs e)
        {
            BrowseClicked?.Invoke(this, EventArgs.Empty);
        }

        private void BtnMore_Clicked(object? sender, EventArgs e)
        {
            using var dlg = AdvancedDownloadDialog.CreateFromGladeFile(this, this.windowGroup);
            dlg.Authentication = Authentication;
            dlg.Proxy = Proxy;
            dlg.EnableSpeedLimit = EnableSpeedLimit;
            dlg.SpeedLimit = SpeedLimit;
            dlg.Run();
            if (dlg.Result)
            {
                Authentication = dlg.Authentication;
                Proxy = dlg.Proxy;
                EnableSpeedLimit = dlg.EnableSpeedLimit;
                SpeedLimit = dlg.SpeedLimit;
            }
            dlg.Destroy();
        }

        private void BtnDownloadNow_Clicked(object? sender, EventArgs e)
        {
            DownloadClicked?.Invoke(this, EventArgs.Empty);
        }

        private void ChkSelectAll_Toggled(object? sender, EventArgs e)
        {
            GtkHelper.ListStoreForEach(store, iter =>
            {
                store.SetValue(iter, 0, ChkSelectAll.Active);
                var ent = (IDownloadEntryWrapper)store.GetValue(iter, 2);
                ent.IsSelected = ChkSelectAll.Active;
            });
        }

        private void CheckboxRenderer_Toggled(object o, ToggledArgs args)
        {
            TreeIter iter;
            if (store.GetIter(out iter, new TreePath(args.Path)))
            {
                var value=!(bool)store.GetValue(iter, 0);
                store.SetValue(iter, 0, value);
                var ent = (IDownloadEntryWrapper)store.GetValue(iter, 2);
                ent.IsSelected = value;
            }
        }

        private void LoadTexts()
        {
            ChkSelectAll.Label = TextResource.GetText("VID_CHK");
            LblSaveIn.Text = TextResource.GetText("LBL_SAVE_IN");
            BtnMore.Label = TextResource.GetText("ND_MORE");
            BtnDownloadLater.Label = TextResource.GetText("ND_DOWNLOAD_LATER");
            BtnDownloadNow.Label = TextResource.GetText("ND_DOWNLOAD_NOW");
        }

        public static DownloadSelectionWindow CreateFromGladeFile()
        {
            return new DownloadSelectionWindow(GtkHelper.GetBuilder("download-selection-window"));
        }
    }

    internal class DownloadEntryWrapper : IDownloadEntryWrapper
    {
        public string Name { get; set; }
        public bool IsSelected { get; set; }
        public IRequestData DownloadEntry { get; set; }
        public string EntryType { get; set; }
    }
}
