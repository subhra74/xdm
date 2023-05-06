using System;
using System.Collections.Generic;
using Gtk;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Core;
using XDM.Core.Util;
using XDM.Core.UI;
using Translations;
using Menu = Gtk.Menu;
using MenuItem = Gtk.MenuItem;
using XDM.GtkUI.Utils;
using XDM.GtkUI.Dialogs.DeleteConfirm;
using XDM.GtkUI.Dialogs.Language;

namespace XDM.GtkUI
{
    public class MainWindow : Window, IApplicationWindow
    {
        private TreeStore categoryTreeStore;
        private TreeView categoryTree;
        private ListStore inprogressDownloadsStore, finishedDownloadsStore;
        private TreeView lvInprogress, lvFinished;
        private ScrolledWindow swInProgress, swFinished;
        private TreeModelFilter finishedDownloadFilter;
        private TreeModelFilter inprogressDownloadFilter;
        private TreeModelSort inprogressDownloadsStoreSorted;
        private TreeModelSort finishedDownloadsStoreSorted;
        private string? searchKeyword;
        private Category? category;
        private Button btnNew, btnDel, btnOpenFile, btnOpenFolder, btnResume, btnPause, btnMenu, btnHelp, btnScheduler;
        private IButton newButton, deleteButton, pauseButton, resumeButton, openFileButton, openFolderButton;
        private IMenuItem[] menuItems;
        private Menu newDownloadMenu;
        private Menu mainMenu;
        private WindowGroup windowGroup;
        private CheckButton btnMonitoring;
        private bool isUpdateAvailable;
        private Image helpImage;
        private Label helpLabel;
        private StatusIcon statusIcon;

        internal WindowGroup GetWindowGroup() => this.windowGroup;

        public IEnumerable<FinishedDownloadItem> FinishedDownloads
        {
            get => GetAllFinishedDownloads();
            set => SetFinishedDownloads(value);
        }

        public IEnumerable<InProgressDownloadItem> InProgressDownloads
        {
            get => GetAllInProgressDownloads();
            set => SetInProgressDownloads(value);
        }

        public IList<IInProgressDownloadRow> SelectedInProgressRows => GetSelectedInProgressDownloads();

        public IList<IFinishedDownloadRow> SelectedFinishedRows => GetSelectedFinishedDownloads();

        public IButton NewButton => this.newButton;

        public IButton DeleteButton => this.deleteButton;

        public IButton PauseButton => this.pauseButton;

        public IButton ResumeButton => this.resumeButton;

        public IButton OpenFileButton => this.openFileButton;

        public IButton OpenFolderButton => this.openFolderButton;

        public bool IsInProgressViewSelected => GetSelectedCategory() == 0;

        public IMenuItem[] MenuItems => this.menuItems;

        public Dictionary<string, IMenuItem> MenuItemMap { get; private set; }

        public event EventHandler ClipboardChanged;
        public event EventHandler InProgressContextMenuOpening;
        public event EventHandler FinishedContextMenuOpening;
        public event EventHandler SelectionChanged;
        public event EventHandler NewDownloadClicked;
        public event EventHandler YoutubeDLDownloadClicked;
        public event EventHandler BatchDownloadClicked;
        public event EventHandler SettingsClicked;
        public event EventHandler ClearAllFinishedClicked;
        public event EventHandler ExportClicked;
        public event EventHandler ImportClicked;
        public event EventHandler BrowserMonitoringButtonClicked;
        public event EventHandler BrowserMonitoringSettingsClicked;
        public event EventHandler UpdateClicked;
        public event EventHandler HelpClicked;
        public event EventHandler SupportPageClicked;
        public event EventHandler BugReportClicked;
        public event EventHandler CheckForUpdateClicked;
        public event EventHandler<CategoryChangedEventArgs> CategoryChanged;
        public event EventHandler SchedulerClicked;
        public event EventHandler DownloadListDoubleClicked;
        public event EventHandler WindowCreated;

        private const int FINISHED_DATA_INDEX = 3;
        private const int INPROGRESS_DATA_INDEX = 5;

        private Menu menuInProgress, menuFinished;
        private IPlatformClipboardMonitor clipboarMonitor;

        public MainWindow() : base("Xtreme Download Manager")
        {
            SetDefaultIconFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "xdm-logo-512.png"));
            SetPosition(WindowPosition.CenterAlways);
            DeleteEvent += AppWin1_DeleteEvent;
            this.windowGroup = new WindowGroup();
            this.windowGroup.AddWindow(this);

            var hbMain = new HBox();
            hbMain.PackStart(CreateCategoryTree(), false, true, 0);
            hbMain.PackStart(CreateMainPanel(), true, true, 0);
            Add(hbMain);
            hbMain.Show();

            categoryTreeStore!.GetIterFirst(out TreeIter iter);
            categoryTreeStore.IterNext(ref iter);
            categoryTree!.Selection.SelectIter(iter);
            UpdateBrowserMonitorButton();
            CreateMenu();
            SetDefaultSize(800, 500);

            clipboarMonitor = new PollingClipboardMonitor();
            clipboarMonitor.ClipboardChanged += (_, _) => this.ClipboardChanged?.Invoke(this, EventArgs.Empty);

            statusIcon = new StatusIcon(GtkHelper.LoadSvg("xdm-logo", 128));
            statusIcon.Activate += StatusIcon_Activate;
        }

        private void StatusIcon_Activate(object? sender, EventArgs e)
        {
            ShowAndActivate();
        }

        private void CreateMenu()
        {
            menuItems = new IMenuItem[]
            {
                new MenuItemWrapper("pause",TextResource.GetText("MENU_PAUSE")),
                new MenuItemWrapper("resume",TextResource.GetText("MENU_RESUME")),
                new MenuItemWrapper("delete",TextResource.GetText("DESC_DEL")),
                new MenuItemWrapper("saveAs",TextResource.GetText("CTX_SAVE_AS")),
                new MenuItemWrapper("refresh",TextResource.GetText("MENU_REFRESH_LINK")),
                new MenuItemWrapper("showProgress",TextResource.GetText("LBL_SHOW_PROGRESS")),
                new MenuItemWrapper("copyURL",TextResource.GetText("CTX_COPY_URL")),
                new MenuItemWrapper("restart",TextResource.GetText("MENU_RESTART")),
                new MenuItemWrapper("moveToQueue",TextResource.GetText("Q_MOVE_TO")),
                new MenuItemWrapper("properties",TextResource.GetText("MENU_PROPERTIES")),

                new MenuItemWrapper("open",TextResource.GetText("CTX_OPEN_FILE")),
                new MenuItemWrapper("openFolder",TextResource.GetText("CTX_OPEN_FOLDER")),
                new MenuItemWrapper("deleteDownloads",TextResource.GetText("MENU_DELETE_DWN")),
                new MenuItemWrapper("copyURL1",TextResource.GetText("CTX_COPY_URL")),
                new MenuItemWrapper("copyFile",TextResource.GetText("CTX_COPY_FILE")),
                new MenuItemWrapper("downloadAgain",TextResource.GetText("MENU_RESTART")),
                new MenuItemWrapper("properties1",TextResource.GetText("MENU_PROPERTIES")),
                new MenuItemWrapper("schedule",TextResource.GetText("Q_SCHEDULE_TXT"),false)
            };

            var dict = new Dictionary<string, IMenuItem>();
            foreach (var mi in menuItems)
            {
                dict[mi.Name] = mi;
            }

            this.MenuItemMap = dict;

            menuFinished = new Menu();
            menuFinished.Append(((MenuItemWrapper)dict["open"]).MenuItem);
            menuFinished.Append(((MenuItemWrapper)dict["openFolder"]).MenuItem);
            menuFinished.Append(((MenuItemWrapper)dict["deleteDownloads"]).MenuItem);
            menuFinished.Append(((MenuItemWrapper)dict["downloadAgain"]).MenuItem);
            menuFinished.Append(((MenuItemWrapper)dict["copyURL1"]).MenuItem);
            menuFinished.Append(((MenuItemWrapper)dict["copyFile"]).MenuItem);
            menuFinished.Append(((MenuItemWrapper)dict["properties1"]).MenuItem);
            menuFinished.ShowAll();

            menuInProgress = new Menu();
            menuInProgress.Append(((MenuItemWrapper)dict["pause"]).MenuItem);
            menuInProgress.Append(((MenuItemWrapper)dict["resume"]).MenuItem);
            menuInProgress.Append(((MenuItemWrapper)dict["delete"]).MenuItem);
            menuInProgress.Append(((MenuItemWrapper)dict["saveAs"]).MenuItem);
            menuInProgress.Append(((MenuItemWrapper)dict["refresh"]).MenuItem);
            menuInProgress.Append(((MenuItemWrapper)dict["restart"]).MenuItem);
            menuInProgress.Append(((MenuItemWrapper)dict["schedule"]).MenuItem);
            menuInProgress.Append(((MenuItemWrapper)dict["showProgress"]).MenuItem);
            menuInProgress.Append(((MenuItemWrapper)dict["copyURL"]).MenuItem);
            menuInProgress.Append(((MenuItemWrapper)dict["moveToQueue"]).MenuItem);
            menuInProgress.Append(((MenuItemWrapper)dict["properties"]).MenuItem);
            menuInProgress.ShowAll();

            newDownloadMenu = new Menu();
            var menuNewDownload = new MenuItem(TextResource.GetText("LBL_NEW_DOWNLOAD"));
            menuNewDownload.Activated += MenuNewDownload_Click;
            var menuVideoDownload = new MenuItem(TextResource.GetText("LBL_VIDEO_DOWNLOAD"));
            menuVideoDownload.Activated += MenuVideoDownload_Click;
            var menuBatchDownload = new MenuItem(TextResource.GetText("MENU_BATCH_DOWNLOAD"));
            menuBatchDownload.Activated += MenuBatchDownload_Click;
            newDownloadMenu.Append(menuNewDownload);
            newDownloadMenu.Append(menuVideoDownload);
            newDownloadMenu.Append(menuBatchDownload);
            newDownloadMenu.ShowAll();

            mainMenu = new Menu();
            var menuSettings = new MenuItem(TextResource.GetText("TITLE_SETTINGS"));
            var menuBrowserMonitor = new MenuItem(TextResource.GetText("SETTINGS_MONITORING"));
            var menuMediaGrabber = new MenuItem(TextResource.GetText("MSG_MEDIA_CAPTURE"));
            var menuClearFinished = new MenuItem(TextResource.GetText("MENU_DELETE_COMPLETED"));
            var menuExport = new MenuItem(TextResource.GetText("MENU_EXPORT"));
            var menuImport = new MenuItem(TextResource.GetText("MENU_IMPORT"));
            var menuLanguage = new MenuItem(TextResource.GetText("MENU_LANG"));
            var menuHelpAndSupport = new MenuItem(TextResource.GetText("LBL_SUPPORT_PAGE"));
            var menuReportProblem = new MenuItem(TextResource.GetText("LBL_REPORT_PROBLEM"));
            var menuCheckForUpdate = new MenuItem(TextResource.GetText("MENU_UPDATE"));
            var menuAbout = new MenuItem(TextResource.GetText("MENU_ABOUT"));
            var menuExit = new MenuItem(TextResource.GetText("MENU_EXIT"));
            menuSettings.Activated += MenuSettings_Activated;
            menuClearFinished.Activated += MenuClearFinished_Activated;
            menuExport.Activated += MenuExport_Activated;
            menuImport.Activated += MenuImport_Activated;
            menuLanguage.Activated += MenuLanguage_Activated;
            menuBrowserMonitor.Activated += MenuBrowserMonitor_Activated;
            menuHelpAndSupport.Activated += MenuHelpAndSupport_Activated;
            menuReportProblem.Activated += MenuReportProblem_Activated;
            menuCheckForUpdate.Activated += MenuCheckForUpdate_Activated;
            menuAbout.Activated += MenuAbout_Activated;
            menuExit.Activated += MenuExit_Activated;
            menuMediaGrabber.Activated += MenuMediaGrabber_Activated;
            mainMenu.Append(menuSettings);
            mainMenu.Append(menuBrowserMonitor);
            mainMenu.Append(menuMediaGrabber);
            mainMenu.Append(menuClearFinished);
            mainMenu.Append(menuExport);
            mainMenu.Append(menuImport);
            mainMenu.Append(menuLanguage);
            mainMenu.Append(menuHelpAndSupport);
            mainMenu.Append(menuReportProblem);
            mainMenu.Append(menuCheckForUpdate);
            mainMenu.Append(menuAbout);
            mainMenu.Append(menuExit);
            mainMenu.ShowAll();
        }

        private void MenuMediaGrabber_Activated(object? sender, EventArgs e)
        {
            ApplicationContext.PlatformUIService.CreateAndShowMediaGrabber();
        }

        private void MenuExit_Activated(object? sender, EventArgs e)
        {
            Application.Quit();
            Environment.Exit(0);
        }

        private void MenuAbout_Activated(object? sender, EventArgs e)
        {
            using var win = XDM.GtkUI.Dialogs.About.AboutDialog.CreateFromGladeFile(this, windowGroup);
            win.Run();
            win.Destroy();
        }

        private void MenuCheckForUpdate_Activated(object? sender, EventArgs e)
        {
            UpdateClicked?.Invoke(sender, e);
        }

        private void MenuReportProblem_Activated(object? sender, EventArgs e)
        {
            BugReportClicked?.Invoke(sender, e);
        }

        private void MenuHelpAndSupport_Activated(object? sender, EventArgs e)
        {
            SupportPageClicked?.Invoke(sender, e);
        }

        private void MenuBrowserMonitor_Activated(object? sender, EventArgs e)
        {
            this.BrowserMonitoringSettingsClicked?.Invoke(this, e);
        }

        private void MenuLanguage_Activated(object? sender, EventArgs e)
        {
            using var win = LanguageDialog.CreateFromGladeFile(this, windowGroup);
            win.Run();
            win.Destroy();
        }

        private void MenuImport_Activated(object? sender, EventArgs e)
        {
            ImportClicked?.Invoke(sender, e);
        }

        private void MenuExport_Activated(object? sender, EventArgs e)
        {
            ExportClicked?.Invoke(sender, e);
        }

        private void MenuClearFinished_Activated(object? sender, EventArgs e)
        {
            this.ClearAllFinishedClicked?.Invoke(sender, e);
        }

        private void MenuSettings_Activated(object? sender, EventArgs e)
        {
            this.SettingsClicked?.Invoke(this, e);
        }

        private void MenuBatchDownload_Click(object? sender, EventArgs e)
        {
            this.BatchDownloadClicked?.Invoke(sender, e);
        }

        private void MenuVideoDownload_Click(object? sender, EventArgs e)
        {
            this.YoutubeDLDownloadClicked?.Invoke(sender, e);
        }

        private void MenuNewDownload_Click(object? sender, EventArgs e)
        {
            this.NewDownloadClicked?.Invoke(sender, e);
        }

        private Widget CreateMainPanel()
        {
            var vbMain = new VBox();
            vbMain.PackStart(CreateToolbar(), false, false, 0);
            vbMain.PackStart(CreateInProgressListView(), true, true, 0);
            vbMain.PackStart(CreateFinishedListView(), true, true, 0);
            vbMain.PackStart(CreateBottombar(), false, false, 0);
            vbMain.Show();
            return vbMain;
        }

        private Button CreateButtonWithContent(string icon, string? text = null)
        {
            Label? lbl = null;
            if (!string.IsNullOrEmpty(text))
            {
                lbl = new Label { Text = text };
            }
            return CreateButtonWithContent(new Image(LoadSvg(icon, 16)), lbl);
        }

        private Button CreateButtonWithContent(Image image, Label? label)
        {
            var hbox = new HBox(false, 10)
            {
                MarginStart = 2,
                MarginEnd = 2
            };

            hbox.PackStart(image, false, false, 0);
            if (label != null)
            {
                hbox.PackStart(label, false, false, 0);
            }

            var button = new Button
            {
                Relief = ReliefStyle.None,
                Valign = Align.Center,

            };
            button.Add(hbox);
            return button;
        }

        private Widget CreateBottombar()
        {
            var hbox = new HBox(false, 10);
            hbox.Margin = 2;
            hbox.MarginStart = 5;
            hbox.MarginEnd = 5;
            //var lblMonitoring = new Label { Text = TextResource.GetText("SETTINGS_MONITORING"), MarginBottom = 5 };
            //hbox.PackStart(lblMonitoring, false, false, 0);
            btnMonitoring = new CheckButton { MarginStart = 5 };
            btnMonitoring.Clicked += BtnMonitoring_Clicked;
            hbox.PackStart(btnMonitoring, false, false, 0);

            var lblMonitoring = new Label { Text = TextResource.GetText("SETTINGS_MONITORING") };
            hbox.PackStart(lblMonitoring, false, false, 0);

            //var h1 = new HBox();
            //h1.PackStart(new Image(LoadSvg("links-line", 14)), false, false, 0);
            //h1.PackStart(new Label { Text = TextResource.GetText("DESC_Q_TITLE") }, false, false, 10);

            btnScheduler = CreateButtonWithContent("list-settings-fill", TextResource.GetText("DESC_Q_TITLE"));
            btnScheduler.Clicked += BtnScheduler_Clicked;
            //new Button
            //{
            //    Label = TextResource.GetText("DESC_Q_TITLE"),
            //    MarginBottom = 0,
            //    Relief = ReliefStyle.None,
            //    Valign = Align.Start,
            //    Image = new Image(LoadSvg("list-settings-fill", 16)),
            //    AlwaysShowImage = true,

            //};
            //btnScheduler.Add(h1);
            //btnScheduler.Margin = 1;
            hbox.PackStart(btnScheduler, false, false, 0);

            helpImage = new Image(LoadSvg("question-line", 16));
            helpLabel = new Label { Text = TextResource.GetText("LBL_SUPPORT_PAGE") };
            btnHelp = CreateButtonWithContent(helpImage, helpLabel);
            btnHelp.Clicked += BtnHelp_Clicked;
            //btnHelp.Margin = 1;
            //btnHelp.MarginEnd = 5;
            //new Button
            //{
            //    Label = TextResource.GetText("LBL_SUPPORT_PAGE"),
            //    MarginBottom = 0,
            //    Relief = ReliefStyle.None,
            //    Valign = Align.Start,
            //    Image = new Image(LoadSvg("question-line", 16)),
            //    AlwaysShowImage = true,
            //};
            hbox.PackEnd(btnHelp, false, false, 0);

            hbox.ShowAll();
            return hbox;
        }

        private void BtnHelp_Clicked(object? sender, EventArgs e)
        {
            if (isUpdateAvailable)
            {
                UpdateClicked?.Invoke(sender, e);
            }
            else
            {
                HelpClicked?.Invoke(sender, e);
            }
        }

        private void BtnScheduler_Clicked(object? sender, EventArgs e)
        {
            //using var dlg = QueueSchedulerDialog.CreateFromGladeFile(this, this.windowGroup);
            //dlg.Run();
            //dlg.Destroy();
            this.SchedulerClicked?.Invoke(sender, e);
        }

        private void BtnMonitoring_Clicked(object? sender, EventArgs e)
        {
            BrowserMonitoringButtonClicked?.Invoke(sender, e);
        }

        private Widget CreateToolbar()
        {
            var toolbar = new HBox(false, 5);
            btnNew = CreateButtonWithContent("links-line", TextResource.GetText("DESC_NEW"));
            toolbar.PackStart(btnNew, false, false, 0);
            btnDel = CreateButtonWithContent("delete-bin-7-line", TextResource.GetText("DESC_DEL"));
            toolbar.PackStart(btnDel, false, false, 0);
            btnOpenFile = CreateButtonWithContent("external-link-line", TextResource.GetText("CTX_OPEN_FILE"));
            toolbar.PackStart(btnOpenFile, false, false, 0);
            btnOpenFolder = CreateButtonWithContent("folder-shared-line", TextResource.GetText("CTX_OPEN_FOLDER"));
            toolbar.PackStart(btnOpenFolder, false, false, 0);
            btnResume = CreateButtonWithContent("play-line", TextResource.GetText("MENU_RESUME"));
            toolbar.PackStart(btnResume, false, false, 0);
            btnPause = CreateButtonWithContent("pause-line", TextResource.GetText("MENU_PAUSE"));
            toolbar.PackStart(btnPause, false, false, 0);

            btnMenu = CreateButtonWithContent("menu-line");
            toolbar.PackEnd(btnMenu, false, false, 0);

            var searchEntry = new Entry() { WidthChars = 15, PlaceholderText = TextResource.GetText("LBL_SEARCH") };
            searchEntry.Activated += (a, b) =>
            {
                searchKeyword = searchEntry.Text;
                finishedDownloadFilter.Refilter();
            };
            toolbar.PackEnd(searchEntry, false, false, 0);
            toolbar.Margin = 5;
            toolbar.ShowAll();

            btnOpenFile.Visible = false;
            btnOpenFolder.Visible = false;
            btnResume.Visible = false;
            btnPause.Visible = false;
            newButton = new ButtonWrapper(this.btnNew);
            deleteButton = new ButtonWrapper(this.btnDel);
            pauseButton = new ButtonWrapper(this.btnPause);
            resumeButton = new ButtonWrapper(this.btnResume);
            openFileButton = new ButtonWrapper(this.btnOpenFile);
            openFolderButton = new ButtonWrapper(this.btnOpenFolder);

            btnMenu.Clicked += BtnMenu_Clicked;

            return toolbar;
        }

        private void BtnMenu_Clicked(object? sender, EventArgs e)
        {
            OpenMainMenu();
        }

        private Widget CreateCategoryTree()
        {
            string GetFontIcon(string name)
            {
                switch (name)
                {
                    case "CAT_DOCUMENTS":
                        return "file-text-line";
                    case "CAT_MUSIC":
                        return "file-music-line";
                    case "CAT_VIDEOS":
                        return "movie-line";
                    case "CAT_COMPRESSED":
                        return "file-zip-line";
                    case "CAT_PROGRAMS":
                        return "function-line";
                    default:
                        return "file-line";
                }
            }

            categoryTree = new TreeView()
            {
                HeadersVisible = false,
                ShowExpanders = false,
                LevelIndentation = 15
            };
            categoryTree.StyleContext.AddClass("dark");

            var cols = new TreeViewColumn();

            var cell1 = new CellRendererPixbuf();
            cell1.SetPadding(3, 5);
            cols.PackStart(cell1, false);
            cols.AddAttribute(cell1, "pixbuf", 0);

            var cell2 = new CellRendererText();
            cell2.SetPadding(0, 5);
            cols.PackStart(cell2, true);
            cols.AddAttribute(cell2, "text", 1);

            categoryTreeStore = new TreeStore(typeof(Gdk.Pixbuf), typeof(string), typeof(Category));
            categoryTreeStore.AppendValues(LoadSvg("arrow-down-line"), TextResource.GetText("ALL_UNFINISHED"));
            var iter = categoryTreeStore.AppendValues(LoadSvg("check-line"), TextResource.GetText("ALL_FINISHED"));

            foreach (var category in Config.Instance.Categories)
            {
                categoryTreeStore.AppendValues(iter, LoadSvg(GetFontIcon(category.Name), 20),
                    category.DisplayName, category);
            }

            categoryTree.AppendColumn(cols);
            categoryTree.Model = categoryTreeStore;
            categoryTree.Selection.Mode = SelectionMode.Browse;
            categoryTree.ExpandAll();
            categoryTree.Selection.Changed += OnCategoryChanged;

            var scrolledWindow = new ScrolledWindow
            {
                OverlayScrolling = true,
                Margin = 5,
                MarginEnd = 0
            };
            //scrolledWindow.Margin = 5;
            scrolledWindow.ShadowType = ShadowType.In;
            scrolledWindow.SetPolicy(PolicyType.Automatic, PolicyType.Automatic);
            scrolledWindow.Add(categoryTree);
            scrolledWindow.SetSizeRequest(160, 200);

            scrolledWindow.ShowAll();
            return scrolledWindow;
        }

        private void OnCategoryChanged(object? sender, EventArgs e)
        {
            if (lvInprogress == null || lvFinished == null)
            {
                return;
            }
            var paths = categoryTree.Selection.GetSelectedRows();
            if (paths == null || paths.Length == 0)
            {
                return;
            }

            if (paths[0].Depth == 1)
            {
                var index = paths[0].Indices[0];
                if (index == 0)
                {
                    swInProgress.ShowAll();
                    swFinished.Hide();
                    category = null;
                    btnOpenFile.Visible = btnOpenFolder.Visible = false;
                    btnPause.Visible = btnResume.Visible = true;
                }
                else
                {
                    swFinished.ShowAll();
                    swInProgress.Hide();
                    category = null;
                    btnOpenFile.Visible = btnOpenFolder.Visible = true;
                    btnPause.Visible = btnResume.Visible = false;
                    finishedDownloadFilter.Refilter();
                }
            }
            else
            {
                swFinished.ShowAll();
                swInProgress.Hide();
                if (categoryTree.Selection.GetSelected(out ITreeModel model, out TreeIter iter))
                {
                    category = (Category)model.GetValue(iter, 2);
                }
                finishedDownloadFilter.Refilter();
            }
        }

        private Widget CreateInProgressListView()
        {
            inprogressDownloadsStore = new ListStore(typeof(string),        // file name
                typeof(string),                                             // date modified
                typeof(string),                                             // size
                typeof(int),                                                // progress
                typeof(string),                                             // status
                typeof(InProgressDownloadItem)                             // download type
                );

            inprogressDownloadFilter = new TreeModelFilter(inprogressDownloadsStore, null);
            inprogressDownloadFilter.VisibleFunc = (model, iter) =>
            {
                var name = (string)model.GetValue(iter, 0);
                return Helpers.IsOfCategoryOrMatchesKeyword(name, searchKeyword, category);
            };

            var sortedStore = new TreeModelSort(inprogressDownloadFilter);

            sortedStore.SetSortFunc(0, (model, iter1, iter2) =>
            {
                Console.WriteLine("called");
                var t1 = (string)model.GetValue(iter1, 0);
                var t2 = (string)model.GetValue(iter2, 0);
                if (t1 == null && t2 == null) return 0;
                if (t1 == null) return 1;
                if (t2 == null) return 2;
                return t1.CompareTo(t2);
            });

            sortedStore.SetSortFunc(1, (model, iter1, iter2) =>
            {
                var t1 = (InProgressDownloadItem)model.GetValue(iter1, 5);
                var t2 = (InProgressDownloadItem)model.GetValue(iter2, 5);
                if (t1 == null && t2 == null) return 0;
                if (t1 == null) return 1;
                if (t2 == null) return 2;
                return t1.DateAdded.CompareTo(t2.DateAdded);
            });

            sortedStore.SetSortFunc(2, (model, iter1, iter2) =>
            {
                var t1 = (InProgressDownloadItem)model.GetValue(iter1, 5);
                var t2 = (InProgressDownloadItem)model.GetValue(iter2, 5);
                if (t1 == null && t2 == null) return 0;
                if (t1 == null) return 1;
                if (t2 == null) return 2;
                return t1.Size.CompareTo(t2.Size);
            });

            inprogressDownloadsStoreSorted = sortedStore;
            lvInprogress = new TreeView(sortedStore);
            lvInprogress.Selection.Mode = SelectionMode.Multiple;

            //File name column
            var fileNameColumn = new TreeViewColumn
            {
                Resizable = true,
                Reorderable = false,
                Title = TextResource.GetText("SORT_NAME"),
                Sizing = TreeViewColumnSizing.Fixed,
                FixedWidth = 200
            };

            var fileIconRenderer = new CellRendererPixbuf { };
            fileIconRenderer.SetPadding(5, 5);
            fileNameColumn.PackStart(fileIconRenderer, false);
            fileNameColumn.SetCellDataFunc(fileIconRenderer, new CellLayoutDataFunc(GetFileIcon));

            var fileNameRendererText = new CellRendererText();
            fileNameColumn.PackStart(fileNameRendererText, false);
            fileNameColumn.SetAttributes(fileNameRendererText, "text", 0);
            lvInprogress.AppendColumn(fileNameColumn);

            //Last modified column
            var lastModifiedRendererText = new CellRendererText();
            var lastModifiedColumn = new TreeViewColumn(TextResource.GetText("SORT_DATE"), lastModifiedRendererText, "text", 1)
            {
                Resizable = true,
                Reorderable = false,
                Sizing = TreeViewColumnSizing.Fixed,
                FixedWidth = 120
            };
            lastModifiedColumn.SortColumnId = 1;
            lastModifiedColumn.SortOrder = SortType.Descending;
            lastModifiedColumn.SetAttributes(lastModifiedRendererText, "text", 1);
            lvInprogress.AppendColumn(lastModifiedColumn);


            //File size column
            var fileSizeRendererText = new CellRendererText();
            //fileSizeRendererText.Xalign = 1.0f;
            var fileSizeColumn = new TreeViewColumn
            {
                Resizable = true,
                Reorderable = false,
                Sizing = TreeViewColumnSizing.Fixed,
                FixedWidth = 80,
                Title = TextResource.GetText("SORT_SIZE"),
            };
            fileSizeColumn.PackStart(fileSizeRendererText, false);
            fileSizeColumn.SetAttributes(fileSizeRendererText, "text", 2);
            lvInprogress.AppendColumn(fileSizeColumn);

            //File progress column
            var fileRendererProgress = new CellRendererProgress()
            {
                //Text = "Downloading",
            };
            fileRendererProgress.SetPadding(5, 10);

            var progressColumn = new TreeViewColumn("%", fileRendererProgress, "value", 3)
            {
                Resizable = true,
                Reorderable = false,
                Sizing = TreeViewColumnSizing.Fixed,
                FixedWidth = 80
            };
            progressColumn.SetAttributes(fileRendererProgress, "value", 3);
            lvInprogress.AppendColumn(progressColumn);

            //Download status column
            var statusRendererText = new CellRendererText();
            statusRendererText.SetPadding(5, 8);
            var statusColumn = new TreeViewColumn(TextResource.GetText("SORT_STATUS"), statusRendererText, "text", 4)
            {
                Resizable = true,
                Reorderable = false,
                Sizing = TreeViewColumnSizing.Fixed,
                FixedWidth = 80
            };
            statusColumn.SetAttributes(statusRendererText, "text", 4);
            lvInprogress.AppendColumn(statusColumn);

            lvInprogress.Selection.Changed += (_, _) =>
            {
                SelectionChanged?.Invoke(this, EventArgs.Empty);
            };

            lvInprogress.ButtonReleaseEvent += (a, b) =>
            {
                if (b.Event.Type == Gdk.EventType.ButtonRelease && b.Event.Button == 3)
                {
                    InProgressContextMenuOpening?.Invoke(this, EventArgs.Empty);
                    menuInProgress.PopupAtPointer(b.Event);
                }
            };

            sortedStore.SetSortColumnId(1, SortType.Descending);

            swInProgress = new ScrolledWindow { OverlayScrolling = true, Margin = 5, MarginBottom = 0, MarginTop = 0, ShadowType = ShadowType.In };
            swInProgress.SetPolicy(PolicyType.Automatic, PolicyType.Automatic);
            swInProgress.Add(lvInprogress);
            swInProgress.ShowAll();
            //scrolledWindow.SetSizeRequest(200, 200);

            return swInProgress;
        }

        private Widget CreateFinishedListView()
        {
            finishedDownloadsStore = new ListStore(typeof(string),          // file name
                typeof(string),                                             // date modified
                typeof(string),                                             // size
                typeof(FinishedDownloadItem)                               // download type
                );

            finishedDownloadFilter = new TreeModelFilter(finishedDownloadsStore, null);
            finishedDownloadFilter.VisibleFunc = (model, iter) =>
            {
                var name = (string)model.GetValue(iter, 0);
                return Helpers.IsOfCategoryOrMatchesKeyword(name, searchKeyword, category);
            };

            var sortedStore = new TreeModelSort(finishedDownloadFilter);

            sortedStore.SetSortFunc(0, (model, iter1, iter2) =>
            {
                var t1 = (string)model.GetValue(iter1, 0);
                var t2 = (string)model.GetValue(iter2, 0);

                if (t1 == null && t2 == null) return 0;
                if (t1 == null) return 1;
                if (t2 == null) return 2;

                return t1.CompareTo(t2);
            });

            sortedStore.SetSortFunc(1, (model, iter1, iter2) =>
            {
                var t1 = (FinishedDownloadItem)model.GetValue(iter1, 3);
                var t2 = (FinishedDownloadItem)model.GetValue(iter2, 3);

                if (t1 == null && t2 == null) return 0;
                if (t1 == null) return 1;
                if (t2 == null) return 2;

                return t1.DateAdded.CompareTo(t2.DateAdded);
            });

            sortedStore.SetSortFunc(2, (model, iter1, iter2) =>
            {
                var t1 = (FinishedDownloadItem)model.GetValue(iter1, 3);
                var t2 = (FinishedDownloadItem)model.GetValue(iter2, 3);

                if (t1 == null && t2 == null) return 0;
                if (t1 == null) return 1;
                if (t2 == null) return 2;

                return t1.Size.CompareTo(t2.Size);
            });

            finishedDownloadsStoreSorted = sortedStore;
            lvFinished = new TreeView(sortedStore);
            lvFinished.Selection.Mode = SelectionMode.Multiple;

            //File name column
            var fileNameColumn = new TreeViewColumn
            {
                Resizable = true,
                Reorderable = false,
                Title = TextResource.GetText("SORT_NAME"),
                Sizing = TreeViewColumnSizing.Fixed,
                FixedWidth = 400
            };

            var fileIconRenderer = new CellRendererPixbuf { };
            fileIconRenderer.SetPadding(5, 5);
            fileNameColumn.PackStart(fileIconRenderer, false);
            fileNameColumn.SetCellDataFunc(fileIconRenderer, new CellLayoutDataFunc(GetFileIcon));
            fileNameColumn.SortColumnId = 0;

            var fileNameRendererText = new CellRendererText();
            fileNameColumn.PackStart(fileNameRendererText, false);
            fileNameColumn.SetAttributes(fileNameRendererText, "text", 0);
            lvFinished.AppendColumn(fileNameColumn);

            //Last modified column
            var lastModifiedRendererText = new CellRendererText();
            var lastModifiedColumn = new TreeViewColumn(TextResource.GetText("SORT_DATE"), lastModifiedRendererText, "text", 1)
            {
                Resizable = true,
                Reorderable = false,
                Sizing = TreeViewColumnSizing.Fixed,
                FixedWidth = 120
            };
            lastModifiedColumn.SetAttributes(lastModifiedRendererText, "text", 1);
            lastModifiedColumn.SortColumnId = 1;
            lvFinished.AppendColumn(lastModifiedColumn);


            //File size column
            var fileSizeRendererText = new CellRendererText();
            //fileSizeRendererText.Xalign = 1.0f;
            var fileSizeColumn = new TreeViewColumn
            {
                Resizable = true,
                Reorderable = false,
                Sizing = TreeViewColumnSizing.Fixed,
                FixedWidth = 80,
                Title = TextResource.GetText("SORT_SIZE"),
            };
            fileSizeColumn.PackStart(fileSizeRendererText, false);
            fileSizeColumn.SetAttributes(fileSizeRendererText, "text", 2);
            fileSizeColumn.SortColumnId = 2;
            lvFinished.AppendColumn(fileSizeColumn);

            lvFinished.Selection.Changed += (_, _) =>
            {
                SelectionChanged?.Invoke(this, EventArgs.Empty);
            };

            lvFinished.ButtonReleaseEvent += (a, b) =>
            {
                if (b.Event.Type == Gdk.EventType.ButtonRelease && b.Event.Button == 3)
                {
                    FinishedContextMenuOpening?.Invoke(this, EventArgs.Empty);
                    menuFinished.PopupAtPointer(b.Event);
                }
            };

            sortedStore.SetSortColumnId(1, SortType.Descending);

            swFinished = new ScrolledWindow { OverlayScrolling = true, Margin = 5, MarginBottom = 0, MarginTop = 0, ShadowType = ShadowType.In };
            swFinished.SetPolicy(PolicyType.Automatic, PolicyType.Automatic);
            swFinished.Add(lvFinished);
            swFinished.ShowAll();
            return swFinished;
        }

        void GetFileIcon(ICellLayout cell_layout,
                CellRenderer cell, ITreeModel tree_model, TreeIter iter)
        {
            var name = (string)tree_model.GetValue(iter, 0);
            var pix = LoadSvg(IconResource.GetSVGNameForFileType(name), 20);
            ((CellRendererPixbuf)cell).Pixbuf = pix;
        }

        private void AppWin1_DeleteEvent(object o, DeleteEventArgs args)
        {
            args.RetVal = true;
            this.Hide();
        }

        private static Gdk.Pixbuf LoadSvg(string name, int dimension = 16)
        {
            return GtkHelper.LoadSvg(name, dimension);
            //new Gdk.Pixbuf(
            //    IoPath.Combine(
            //        AppDomain.CurrentDomain.BaseDirectory, "svg-icons", $"{name}.svg"), dimension, dimension, true);
        }

        public IInProgressDownloadRow? FindInProgressItem(string id)
        {
            if (!inprogressDownloadsStore!.GetIterFirst(out TreeIter iter))
            {
                return null;
            }
            do
            {
                var ent = (InProgressDownloadItem)inprogressDownloadsStore.GetValue(iter, INPROGRESS_DATA_INDEX);
                if (ent.Id == id)
                {
                    return new InProgressEntryWrapper(ent, iter, inprogressDownloadsStore);
                }
            }
            while (inprogressDownloadsStore.IterNext(ref iter));
            return null;
        }

        public TreeIter? FindInProgressItemIterById(string id)
        {
            if (!inprogressDownloadsStore!.GetIterFirst(out TreeIter iter))
            {
                return null;
            }
            do
            {
                var ent = (InProgressDownloadItem)inprogressDownloadsStore.GetValue(iter, INPROGRESS_DATA_INDEX);
                if (ent.Id == id)
                {
                    return iter;
                }
            }
            while (inprogressDownloadsStore.IterNext(ref iter));
            return null;
        }

        public IFinishedDownloadRow? FindFinishedItem(string id)
        {
            if (!this.finishedDownloadsStore!.GetIterFirst(out TreeIter iter))
            {
                return null;
            }
            do
            {
                var ent = (FinishedDownloadItem)finishedDownloadsStore.GetValue(iter, FINISHED_DATA_INDEX);
                if (ent.Id == id)
                {
                    return new FinishedEntryWrapper(ent, iter, finishedDownloadsStore);
                }
            }
            while (finishedDownloadsStore.IterNext(ref iter));
            return null;
        }

        public TreeIter? FindFinishedItemIterById(string id)
        {
            if (!this.finishedDownloadsStore!.GetIterFirst(out TreeIter iter))
            {
                return null;
            }
            do
            {
                var ent = (FinishedDownloadItem)finishedDownloadsStore.GetValue(iter, FINISHED_DATA_INDEX);
                if (ent.Id == id)
                {
                    return iter;
                }
            }
            while (finishedDownloadsStore.IterNext(ref iter));
            return null;
        }

        public void AddToTop(InProgressDownloadItem entry)
        {
            var iter = inprogressDownloadsStore.Insert(0);
            inprogressDownloadsStore.SetValue(iter, 0, entry.Name);
            inprogressDownloadsStore.SetValue(iter, 1, entry.DateAdded.ToShortDateString());
            inprogressDownloadsStore.SetValue(iter, 2, FormattingHelper.FormatSize(entry.Size));
            inprogressDownloadsStore.SetValue(iter, 3, entry.Progress);
            inprogressDownloadsStore.SetValue(iter, 4, entry.Status.ToString());
            inprogressDownloadsStore.SetValue(iter, 5, entry);
        }

        public void AddToTop(FinishedDownloadItem entry)
        {
            finishedDownloadsStore.AppendValues(
                entry.Name,
                entry.DateAdded.ToShortDateString(),
                FormattingHelper.FormatSize(entry.Size),
                entry);
            finishedDownloadFilter.Refilter();
            //finishedDownloadsStoreSorted.AppendValues()
            //sortedStore.SetSortColumnId(1, SortType.Descending);
        }

        public void SwitchToInProgressView()
        {
            if (this.categoryTreeStore.GetIterFirst(out TreeIter iter))
            {
                this.categoryTree.Selection.SelectIter(iter);
            }
        }

        public void ClearInProgressViewSelection()
        {
            this.lvInprogress.Selection.UnselectAll();
        }

        public void SwitchToFinishedView()
        {
            if (this.categoryTreeStore.GetIterFirst(out TreeIter iter) &&
                this.categoryTreeStore.IterNext(ref iter))
            {
                this.categoryTree.Selection.SelectIter(iter);
            }
        }

        public void ClearFinishedViewSelection()
        {
            this.lvFinished.Selection.UnselectAll();
        }

        public bool Confirm(object? window, string text)
        {
            if (window is not Window owner)
            {
                owner = this;
            }
            using var msg = new MessageDialog(owner, DialogFlags.Modal, MessageType.Question, ButtonsType.YesNo, text);
            msg.Title = "XDM";
            if (msg.Run() == (int)ResponseType.Yes)
            {
                return true;
            }
            return false;
        }

        public void RunOnUIThread(System.Action action)
        {
            Application.Invoke((a, b) => action.Invoke());
        }

        public void RunOnUIThread(Action<string, int, double, long> action, string id, int progress, double speed, long eta)
        {
            Application.Invoke((a, b) => action.Invoke(id, progress, speed, eta));
        }

        public void Delete(IInProgressDownloadRow row)
        {
            var id = row.DownloadEntry.Id;
            var modelIter = FindInProgressItemIterById(id);
            if (modelIter.HasValue)
            {
                var iter = modelIter.Value;
                inprogressDownloadsStore.Remove(ref iter);
            }

            //var iter = GtkHelper.ConvertViewToModel(((InProgressEntryWrapper)row).TreeIter,
            //    inprogressDownloadsStoreSorted, inprogressDownloadFilter);
            //inprogressDownloadsStore.Remove(ref iter);
        }

        public void Delete(IFinishedDownloadRow row)
        {
            var id = row.DownloadEntry.Id;
            var modelIter = FindFinishedItemIterById(id);
            if (modelIter.HasValue)
            {
                var iter = modelIter.Value;
                finishedDownloadsStore.Remove(ref iter);
            }
            //var iter = GtkHelper.ConvertViewToModel(((FinishedEntryWrapper)row).TreeIter,
            //    finishedDownloadsStoreSorted, finishedDownloadFilter);
        }

        public void DeleteAllFinishedDownloads()
        {
            if (!GtkHelper.ShowConfirmMessageBox(this, TextResource.GetText("MENU_DELETE_COMPLETED"), "XDM"))
            {
                return;
            }
            finishedDownloadsStore.Clear();
        }

        public void Delete(IEnumerable<IInProgressDownloadRow> rows)
        {
            foreach (var row in rows)
            {
                Delete(row);
                //var iter = ((InProgressEntryWrapper)row).TreeIter;
                //inprogressDownloadsStore.Remove(ref iter);
            }
        }

        public void Delete(IEnumerable<IFinishedDownloadRow> rows)
        {
            foreach (var row in rows)
            {
                Delete(row);
                //var iter = ((FinishedEntryWrapper)row).TreeIter;
                //inprogressDownloadsStore.Remove(ref iter);
            }
        }

        public string GetUrlFromClipboard()
        {
            var cb = Clipboard.Get(Gdk.Selection.Clipboard);
            return cb.WaitForText();
        }

        public void OpenNewDownloadMenu()
        {
            newDownloadMenu.PopupAtWidget(this.btnNew, Gdk.Gravity.SouthWest, Gdk.Gravity.NorthWest, null);
        }

        private void OpenMainMenu()
        {
            mainMenu.PopupAtWidget(this.btnMenu, Gdk.Gravity.SouthEast, Gdk.Gravity.NorthEast, null);
        }

        public void SetClipboardText(string text)
        {
            var cb = Clipboard.Get(Gdk.Selection.Clipboard);
            if (cb != null)
            {
                cb.Text = text;
            }
        }

        public void SetClipboardFile(string file)
        {
            var cbcp = new ClipboardFileCopy(file);
            cbcp.Exec();
        }

        public void UpdateBrowserMonitorButton()
        {
            btnMonitoring.Active = Config.Instance.IsBrowserMonitoringEnabled;
        }

        public void ShowUpdateAvailableNotification()
        {
            isUpdateAvailable = true;
            helpLabel.Text = TextResource.GetText("MSG_UPDATE_AVAILABLE");
            helpImage.Pixbuf = LoadSvg("notification-3-fill", 16);
            helpImage.ShowAll();
        }

        public void ClearUpdateInformation()
        {
            RunOnUIThread(() =>
            {
                isUpdateAvailable = false;
                helpLabel.Text = TextResource.GetText("LBL_SUPPORT_PAGE");
                helpImage.Pixbuf = LoadSvg("question-line", 16);
                helpImage.ShowAll();
            });
        }

        private IEnumerable<FinishedDownloadItem> GetAllFinishedDownloads()
        {
            if (!finishedDownloadsStore!.GetIterFirst(out TreeIter iter))
            {
                yield break;
            }
            yield return (FinishedDownloadItem)finishedDownloadsStore.GetValue(iter, FINISHED_DATA_INDEX);
            while (finishedDownloadsStore.IterNext(ref iter))
            {
                yield return (FinishedDownloadItem)finishedDownloadsStore.GetValue(iter, FINISHED_DATA_INDEX);
            }
        }

        private IEnumerable<InProgressDownloadItem> GetAllInProgressDownloads()
        {
            if (!inprogressDownloadsStore!.GetIterFirst(out TreeIter iter))
            {
                yield break;
            }
            yield return (InProgressDownloadItem)inprogressDownloadsStore.GetValue(iter, INPROGRESS_DATA_INDEX);
            while (inprogressDownloadsStore.IterNext(ref iter))
            {
                yield return (InProgressDownloadItem)inprogressDownloadsStore.GetValue(iter, INPROGRESS_DATA_INDEX);
            }
        }

        private void SetFinishedDownloads(IEnumerable<FinishedDownloadItem> finishedDownloads)
        {
            finishedDownloadsStore.Clear();
            foreach (var item in finishedDownloads)
            {
                finishedDownloadsStore.AppendValues(item.Name,
                    item.DateAdded.ToShortDateString(),
                    FormattingHelper.FormatSize(item.Size),
                    item);
            }
        }

        private void SetInProgressDownloads(IEnumerable<InProgressDownloadItem> incompleteDownloads)
        {
            inprogressDownloadsStore.Clear();
            foreach (var item in incompleteDownloads)
            {
                inprogressDownloadsStore.AppendValues(item.Name,
                    item.DateAdded.ToShortDateString(),
                    FormattingHelper.FormatSize(item.Size),
                    item.Progress,
                    Helpers.GenerateStatusText(item),
                    item);
            }
        }

        private IList<IInProgressDownloadRow> GetSelectedInProgressDownloads()
        {
            var list = new List<IInProgressDownloadRow>(0);
            var rows = lvInprogress.Selection.GetSelectedRows(out ITreeModel model);
            if (rows != null && rows.Length > 0)
            {
                list.Capacity = rows.Length;
                foreach (var row in rows)
                {
                    if (model.GetIter(out TreeIter iter, row))
                    {
                        var ent = (InProgressDownloadItem)model.GetValue(iter, INPROGRESS_DATA_INDEX);
                        list.Add(new InProgressEntryWrapper(ent, iter, model));
                    }
                }
            }
            return list;
        }

        private IList<IFinishedDownloadRow> GetSelectedFinishedDownloads()
        {
            var list = new List<IFinishedDownloadRow>(0);
            var rows = lvFinished.Selection.GetSelectedRows(out ITreeModel model);
            if (rows != null && rows.Length > 0)
            {
                list.Capacity = rows.Length;
                foreach (var row in rows)
                {
                    if (model.GetIter(out TreeIter iter, row))
                    {
                        var ent = (FinishedDownloadItem)model.GetValue(iter, FINISHED_DATA_INDEX);
                        list.Add(new FinishedEntryWrapper(ent, iter, model));
                    }
                }
            }
            return list;
        }

        private int GetSelectedCategory()
        {
            var paths = categoryTree.Selection.GetSelectedRows();
            if (paths != null && paths.Length > 0 && paths[0].Depth == 1)
            {
                return paths[0].Indices[0];
            }
            return -1;
        }

        public void ConfirmDelete(string text, out bool approved, out bool deleteFiles)
        {
            approved = false;
            deleteFiles = false;
            using var dlg = DeleteConfirmDialog.CreateFromGladeFile(this, this.windowGroup);
            if (!string.IsNullOrEmpty(text))
            {
                dlg.DescriptionText = text;
            }
            dlg.Run();
            if (dlg.Result)
            {
                approved = true;
                deleteFiles = dlg.ShouldDeleteFile;
            }
            dlg.Destroy();
        }

        public IPlatformClipboardMonitor GetClipboardMonitor() => this.clipboarMonitor;

        public void ShowAndActivate()
        {
            if (!this.Visible)
            {
                this.Show();
            }
            this.Present();
        }
    }
}
