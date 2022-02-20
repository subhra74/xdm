using Microsoft.Win32;
using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using TraceLog;
using Translations;
using XDM.Common.UI;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Downloader;
using XDM.Core.Lib.UI;
using XDM.Core.Lib.Util;
using XDM.Wpf.UI.Dialogs.About;
using XDM.Wpf.UI.Dialogs.BatchDownload;
using XDM.Wpf.UI.Dialogs.CompletedDialog;
using XDM.Wpf.UI.Dialogs.CredentialDialog;
using XDM.Wpf.UI.Dialogs.DeleteConfirm;
using XDM.Wpf.UI.Dialogs.DownloadSelection;
using XDM.Wpf.UI.Dialogs.LanguageSettings;
using XDM.Wpf.UI.Dialogs.NewDownload;
using XDM.Wpf.UI.Dialogs.NewVideoDownload;
using XDM.Wpf.UI.Dialogs.ProgressWindow;
using XDM.Wpf.UI.Dialogs.PropertiesDialog;
using XDM.Wpf.UI.Dialogs.QueuesWindow;
using XDM.Wpf.UI.Dialogs.RefreshLink;
using XDM.Wpf.UI.Dialogs.Settings;
using XDM.Wpf.UI.Dialogs.Updater;
using XDM.Wpf.UI.Dialogs.VideoDownloader;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI
{
    /// <summary>
    /// Interaction logic for MainWindow.xaml
    /// </summary>
    public partial class MainWindow : Window, IAppWinPeer
    {
        private ObservableCollection<InProgressDownloadEntryWrapper> inProgressList
            = new ObservableCollection<InProgressDownloadEntryWrapper>();
        private ObservableCollection<FinishedDownloadEntryWrapper> finishedList
            = new ObservableCollection<FinishedDownloadEntryWrapper>();

        private IButton newButton, deleteButton, pauseButton, resumeButton, openFileButton, openFolderButton;
        private GridViewColumnHeader finishedListViewSortCol = null;
        private SortAdorner finishedListViewSortAdorner = null;
        private GridViewColumnHeader inProgressListViewSortCol = null;
        private SortAdorner inProgressListViewSortAdorner = null;
        private MessageLoop messageLoop;
        private Win32ClipboarMonitor clipboarMonitor;

        private IMenuItem[] menuItems;

        public MainWindow()
        {
            InitializeComponent();

            newButton = new ButtonWrapper(this.BtnNew);
            deleteButton = new ButtonWrapper(this.BtnDelete);
            pauseButton = new ButtonWrapper(this.BtnPause);
            resumeButton = new ButtonWrapper(this.BtnResume);
            openFileButton = new ButtonWrapper(this.BtnOpen);
            openFolderButton = new ButtonWrapper(this.BtnOpenFolder);
            var categories = new List<CategoryWrapper>();
            categories.Add(new CategoryWrapper() { IsTopLevel = true, DisplayName = "Incomplete", VectorIcon = "ri-arrow-down-line" });
            categories.Add(new CategoryWrapper() { IsTopLevel = true, DisplayName = "Complete", VectorIcon = "ri-check-line" });
            categories.AddRange(Config.Instance.Categories.Select(c => new CategoryWrapper(c)
            {
                VectorIcon = IconMap.GetVectorNameForCategory(c.Name)
            }));
            lvCategory.ItemsSource = categories;

            lvInProgress.ItemsSource = inProgressList;
            lvFinished.ItemsSource = finishedList;

            lvInProgress.SelectionChanged += (sender, args) =>
            {
                this.SelectionChanged?.Invoke(sender, args);
            };

            lvFinished.SelectionChanged += (sender, args) =>
            {
                this.SelectionChanged?.Invoke(sender, args);
            };

            lvInProgress.IsVisibleChanged += (_, _) =>
            {
                if (lvInProgress.Visibility == Visibility.Visible)
                {
                    InProgressListViewInitialSortIfNotAlreadySorted();
                }
            };

            SwitchToFinishedView();
            this.Loaded += MainWindow_Loaded;
            CreateMenuItems();
        }

        private void MainWindow_Loaded(object sender, RoutedEventArgs e)
        {
            FinishedListViewInitialSortIfNotAlreadySorted();
            UpdateBrowserMonitorButton();
        }

        private void InProgressListViewInitialSortIfNotAlreadySorted()
        {
            //sort in-progress list view by date
            if (inProgressListViewSortCol == null)
            {
                var col = (GridViewColumnHeader)FindName("lvInProgress_DateAdded");
                var layer = AdornerLayer.GetAdornerLayer(col);
                if (layer != null)
                {
                    inProgressListViewSortCol = col;
                    inProgressListViewSortAdorner = new SortAdorner(inProgressListViewSortCol, ListSortDirection.Descending);
                    layer.Add(inProgressListViewSortAdorner);
                }
                lvInProgress.Items.SortDescriptions.Add(new SortDescription("DateAdded", ListSortDirection.Descending));
            }
        }

        private void FinishedListViewInitialSortIfNotAlreadySorted()
        {
            //sort finished list view by date
            if (finishedListViewSortCol == null)
            {
                var col = (GridViewColumnHeader)FindName("lvFinished_DateAdded");
                var layer = AdornerLayer.GetAdornerLayer(col);
                if (layer != null)
                {
                    finishedListViewSortCol = col;
                    finishedListViewSortAdorner = new SortAdorner(finishedListViewSortCol, ListSortDirection.Descending);
                    layer.Add(finishedListViewSortAdorner);
                }
                //finishedListViewSortCol = (GridViewColumnHeader)FindName("lvFinished_DateAdded");
                //finishedListViewSortAdorner = new SortAdorner(finishedListViewSortCol, ListSortDirection.Descending);
                //AdornerLayer.GetAdornerLayer(finishedListViewSortCol).Add(finishedListViewSortAdorner);
                lvFinished.Items.SortDescriptions.Add(new SortDescription("DateAdded", ListSortDirection.Descending));
            }
        }

        private void lvCategory_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            TxtSearch.Text = string.Empty;
            ApplyFilter();
        }

        private void ApplyFilter()
        {
            var index = lvCategory.SelectedIndex;
            if (index == 0)
            {
                lvInProgress.Visibility = Visibility.Visible;
                lvFinished.Visibility = Visibility.Collapsed;
                InProgressListViewInitialSortIfNotAlreadySorted();

                CategoryChanged?.Invoke(this, new CategoryChangedEventArgs { Level = 0, Index = 0 });
            }
            else if (index > 0)
            {
                lvInProgress.Visibility = Visibility.Collapsed;
                lvFinished.Visibility = Visibility.Visible;

                ListCollectionView view = (ListCollectionView)
                        CollectionViewSource.GetDefaultView(lvFinished.ItemsSource);
                if (index > 1)
                {
                    CategoryWrapper? cat = (CategoryWrapper)lvCategory.SelectedItem;
                    view.Filter = a => IsCategoryMatched((FinishedDownloadEntryWrapper)a, cat);
                    CategoryChanged?.Invoke(this, new CategoryChangedEventArgs
                    {
                        Level = 1,
                        Index = index - 2,
                        Category = cat.category
                    });
                }
                else
                {
                    view.Filter = a => IsCategoryMatched((FinishedDownloadEntryWrapper)a, null);
                    CategoryChanged?.Invoke(this, new CategoryChangedEventArgs { Level = 0, Index = 1 });
                }
            }

            SelectionChanged?.Invoke(this, EventArgs.Empty);
        }

        private bool IsCategoryMatched(FinishedDownloadEntryWrapper entry, CategoryWrapper? category)
        {
            return Helpers.IsOfCategoryOrMatchesKeyword(entry.Name, TxtSearch.Text, category?.category);
        }

        public event EventHandler<CategoryChangedEventArgs> CategoryChanged;
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
        public event EventHandler SchedulerClicked;
        public event EventHandler MoveToQueueClicked;
        public event EventHandler DownloadListDoubleClicked;
        public event EventHandler? ClipboardChanged;

        public IEnumerable<FinishedDownloadEntry> FinishedDownloads
        {
            get => this.finishedList.Select(x => x.DownloadEntry);
            set
            {
                this.finishedList = new ObservableCollection<FinishedDownloadEntryWrapper>(
                    value.Select(x => new FinishedDownloadEntryWrapper(x)));
                this.lvFinished.ItemsSource = finishedList;
                FinishedListViewInitialSortIfNotAlreadySorted();
            }
        }

        public IEnumerable<InProgressDownloadEntry> InProgressDownloads
        {
            get => this.inProgressList.Select(x => x.DownloadEntry);
            set
            {
                this.inProgressList = new ObservableCollection<InProgressDownloadEntryWrapper>(
                    value.Select(x => new InProgressDownloadEntryWrapper(x)));
                this.lvInProgress.ItemsSource = inProgressList;
                InProgressListViewInitialSortIfNotAlreadySorted();
            }
        }

        public IList<IInProgressDownloadRow> SelectedInProgressRows =>
            this.lvInProgress.SelectedItems.OfType<IInProgressDownloadRow>().ToList();

        public IList<IFinishedDownloadRow> SelectedFinishedRows =>
            this.lvFinished.SelectedItems.OfType<IFinishedDownloadRow>().ToList();

        public IButton NewButton => newButton;

        public IButton DeleteButton => deleteButton;

        public IButton PauseButton => pauseButton;

        public IButton ResumeButton => resumeButton;

        public IButton OpenFileButton => openFileButton;

        public IButton OpenFolderButton => openFolderButton;

        public bool IsInProgressViewSelected => lvCategory.SelectedIndex == 0;

        public IMenuItem[] MenuItems => this.menuItems;

        public Dictionary<string, IMenuItem> MenuItemMap { get; private set; }

        public IInProgressDownloadRow FindInProgressItem(string id) =>
            this.lvInProgress.Items.OfType<IInProgressDownloadRow>()
            .Where(x => x.DownloadEntry.Id == id).FirstOrDefault();

        public IFinishedDownloadRow FindFinishedItem(string id) =>
            this.lvFinished.Items.OfType<IFinishedDownloadRow>()
            .Where(x => x.DownloadEntry.Id == id).FirstOrDefault();

        public void AddToTop(InProgressDownloadEntry entry)
        {
            this.inProgressList.Add(new InProgressDownloadEntryWrapper(entry));
        }

        public void AddToTop(FinishedDownloadEntry entry)
        {
            this.finishedList.Add(new FinishedDownloadEntryWrapper(entry));
        }

        public void SwitchToInProgressView()
        {
            lvCategory.SelectedIndex = 0;
        }

        public void ClearInProgressViewSelection()
        {
            lvInProgress.UnselectAll();
        }

        public void SwitchToFinishedView()
        {
            lvCategory.SelectedIndex = 1;
        }

        public void ClearFinishedViewSelection()
        {
            lvFinished.UnselectAll();
        }

        public bool Confirm(object? window, string text)
        {
            return MessageBox.Show((Window)(window ?? this), text, "XDM", MessageBoxButton.YesNo) == MessageBoxResult.Yes;
        }

        public void ConfirmDelete(string text, out bool approved, out bool deleteFiles)
        {
            DeleteConfirmDialog dc = new() { DescriptionText = text, Owner = this };
            approved = false;
            deleteFiles = false;
            bool? ret = dc.ShowDialog(this);
            if (ret.HasValue && ret.Value)
            {
                approved = true;
                deleteFiles = dc.ShouldDeleteFile;
            }
        }

        public IDownloadCompleteDialog CreateDownloadCompleteDialog(IApp app)
        {
            return new DownloadCompleteWindow { App = app };
        }

        public INewDownloadDialogSkeleton CreateNewDownloadDialog(bool empty)
        {
            return new NewDownloadWindow() { IsEmpty = empty };
        }

        public INewVideoDownloadDialog CreateNewVideoDialog()
        {
            return new NewVideoDownloadWindow();
        }

        public IProgressWindow CreateProgressWindow(string downloadId, IApp app, IAppUI appUI)
        {
            return new DownloadProgressWindow
            {
                DownloadId = downloadId,
                App = app,
                AppUI = appUI
            };
        }

        public void RunOnUIThread(Action action)
        {
            Dispatcher.BeginInvoke(action);
        }

        public void RunOnUIThread(Action<string, int, double, long> action, string id, int progress, double speed, long eta)
        {
            Dispatcher.BeginInvoke(action, id, progress, speed, eta);
        }

        public void Delete(IInProgressDownloadRow row)
        {
            this.inProgressList.Remove((InProgressDownloadEntryWrapper)row);
        }

        public void Delete(IFinishedDownloadRow row)
        {
            this.finishedList.Remove((FinishedDownloadEntryWrapper)row);
        }

        public void DeleteAllFinishedDownloads()
        {
            if (MessageBox.Show(this, TextResource.GetText("MENU_DELETE_COMPLETED"), "XDM", MessageBoxButton.YesNo)
                != MessageBoxResult.Yes)
            {
                return;
            }
            finishedList.Clear();
        }

        public void Delete(IEnumerable<IInProgressDownloadRow> rows)
        {
            foreach (var row in rows)
            {
                inProgressList.Remove((InProgressDownloadEntryWrapper)row);
            }
        }

        public void Delete(IEnumerable<IFinishedDownloadRow> rows)
        {
            foreach (var row in rows)
            {
                finishedList.Remove((FinishedDownloadEntryWrapper)row);
            }
        }

        public string? GetUrlFromClipboard()
        {
            var text = Clipboard.GetText();
            if (Helpers.IsUriValid(text))
            {
                return text;
            }
            return null;
        }

        public AuthenticationInfo? PromtForCredentials(string message)
        {
            var dlg = new CredentialsPromptDialog { PromptText = message ?? "Authentication required", Owner = this };
            var ret = dlg.ShowDialog(this);
            if (ret.HasValue && ret.Value)
            {
                return dlg.Credentials;
            }
            return null;
        }

        public void ShowUpdateAvailableNotification()
        {
            RunOnUIThread(() =>
            {
                PathHelp.Data = (Geometry)FindResource("ri-notification-3-fill");
                PathHelp.Fill = (SolidColorBrush)FindResource("color-update-avaliable");
                TxtHelp.Text = TextResource.GetText("MSG_UPDATE_AVAILABLE");
                BtnHelp.Tag = new object();
            });
        }

        public void ClearUpdateInformation()
        {
            RunOnUIThread(() =>
            {
                PathHelp.Data = (Geometry)FindResource("ri-question-line");
                TxtHelp.Text = TextResource.GetText("LBL_SUPPORT_PAGE");
                PathHelp.Fill = (SolidColorBrush)FindResource("StatusbarIconcolor");
                BtnHelp.Tag = null;
            });
        }

        public void ShowMessageBox(object window, string message)
        {
            Dispatcher.Invoke(new Action(() =>
            {
                if (window is IAppUI || window == this || window == null)
                {
                    MessageBox.Show(this, message);
                }
                else
                {
                    MessageBox.Show((Window)window, message);
                }
            }));
        }

        public void OpenNewDownloadMenu()
        {
            var nctx = (ContextMenu)FindResource("newDownloadContextMenu");
            nctx.PlacementTarget = BtnNew;
            nctx.Placement = PlacementMode.Bottom;
            nctx.IsOpen = true;
        }

        public string? SaveFileDialog(string? initialPath, string? defaultExt, string? filter)
        {
            var fc = new SaveFileDialog();
            if (!string.IsNullOrEmpty(initialPath))
            {
                fc.FileName = initialPath;
            }
            if (!string.IsNullOrEmpty(defaultExt))
            {
                fc.DefaultExt = defaultExt;
            }
            if (!string.IsNullOrEmpty(filter))
            {
                fc.Filter = filter;
            }
            var ret = fc.ShowDialog(this);
            if (ret.HasValue && ret.Value)
            {
                return fc.FileName;
            }
            return null;
        }

        public string? OpenFileDialog(string? initialPath, string? defaultExt, string? filter)
        {
            var fc = new OpenFileDialog();
            if (!string.IsNullOrEmpty(initialPath))
            {
                fc.FileName = initialPath;
            }
            if (!string.IsNullOrEmpty(defaultExt))
            {
                fc.DefaultExt = defaultExt;
            }
            if (!string.IsNullOrEmpty(filter))
            {
                fc.Filter = filter;
            }
            var ret = fc.ShowDialog(this);
            if (ret.HasValue && ret.Value)
            {
                return fc.FileName;
            }
            return null;
        }

        public void ShowRefreshLinkDialog(InProgressDownloadEntry entry, IApp app)
        {
            var dlg = new LinkRefreshWindow();
            LinkRefreshDialogHelper.RefreshLink(entry, app, dlg);
        }

        public void SetClipboardText(string text)
        {
            Clipboard.SetText(text);
        }

        public void SetClipboardFile(string file)
        {
            var sc = new StringCollection();
            sc.Add(file);
            Clipboard.SetFileDropList(sc);
        }

        public void ShowPropertiesDialog(BaseDownloadEntry ent, ShortState? state)
        {
            var propertiesWindow = new DownloadPropertiesWindow
            {
                FileName = ent.Name,
                Folder = ent.TargetDir ?? Helpers.GetDownloadFolderByFileName(ent.Name),
                Address = ent.PrimaryUrl,
                FileSize = Helpers.FormatSize(ent.Size),
                DateAdded = ent.DateAdded.ToLongDateString() + " " + ent.DateAdded.ToLongTimeString(),
                DownloadType = ent.DownloadType,
                Referer = ent.RefererUrl,
                Cookies = state?.Cookies ?? state?.Cookies1 ?? new Dictionary<string, string>(),
                Headers = state?.Headers ?? state?.Headers1 ?? new Dictionary<string, List<string>>(),
                Owner = this
            };
            propertiesWindow.ShowDialog(this);
        }

        public void ShowYoutubeDLDialog(IAppUI appUI, IApp app)
        {
            var ydlWindow = new VideoDownloaderWindow(app, appUI) { Owner = this };
            ydlWindow.Show();
        }

        public void ShowBatchDownloadWindow(IApp app, IAppUI appUi)
        {
            var batWin = new BatchDownloadWindow(app, appUi) { Owner = this };
            batWin.Show();
        }

        public void ShowSettingsDialog(IApp app, int page = 0)
        {
            var settings = new SettingsWindow(app) { Owner = this };
            settings.ShowDialog(this);
        }

        public void UpdateBrowserMonitorButton()
        {
            this.MonitoringToggleIcon.Data = (System.Windows.Media.Geometry)FindResource(Config.Instance.IsBrowserMonitoringEnabled ?
                "ri-toggle-fill" : "ri-toggle-line");
        }

        public void ShowBrowserMonitoringDialog(IApp app)
        {
            var settings = new SettingsWindow(app, 0) { Owner = this };
            settings.ShowDialog(this);
        }

        public void UpdateParallalismLabel()
        {

        }

        public IUpdaterUI CreateUpdateUIDialog(IAppUI ui)
        {
            return new UpdaterWindow(ui);
        }

        private void SearchButton_Click(object sender, RoutedEventArgs e)
        {
            ApplyFilter();
        }

        public IQueuesWindow CreateQueuesAndSchedulerWindow(IAppUI appUi)
        {
            return new ManageQueueDialog(appUi)
            {
                Owner = this
            };
            //qmDlg.ShowDialog(this);
            //return this;
        }

        public IQueueSelectionDialog CreateQueueSelectionDialog()
        {
            return new QueueSelectionWindow() { Owner = this };
        }


        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            var helper = new WindowInteropHelper(this);

#if NET45_OR_GREATER
            if (App.Skin == Skin.Dark)
            {
                helper.EnsureHandle();
                DarkModeHelper.UseImmersiveDarkMode(helper.Handle, true);
            }
#endif
            clipboarMonitor = new Win32ClipboarMonitor(helper.Handle);
            clipboarMonitor.ClipboardChanged += (sender, args) => this.ClipboardChanged?.Invoke(this, EventArgs.Empty);
            this.messageLoop = new MessageLoop(clipboarMonitor);
            messageLoop.Start(helper.Handle);
        }


        private void lvFinished_Click(object sender, RoutedEventArgs e)
        {
            if (e.OriginalSource is GridViewColumnHeader column)
            {
                string sortBy = (string)column.Tag;
                if (string.IsNullOrEmpty(sortBy))
                {
                    return;
                }
                if (finishedListViewSortCol != null)
                {
                    AdornerLayer.GetAdornerLayer(finishedListViewSortCol).Remove(finishedListViewSortAdorner);
                    lvFinished.Items.SortDescriptions.Clear();
                }

                ListSortDirection newDir = ListSortDirection.Ascending;
                if (finishedListViewSortCol == column && finishedListViewSortAdorner.Direction == newDir)
                    newDir = ListSortDirection.Descending;

                finishedListViewSortCol = column;
                finishedListViewSortAdorner = new SortAdorner(finishedListViewSortCol, newDir);
                AdornerLayer.GetAdornerLayer(finishedListViewSortCol).Add(finishedListViewSortAdorner);
                lvFinished.Items.SortDescriptions.Add(new SortDescription(sortBy, newDir));
            }
        }

        private void BtnMenu_Click(object sender, RoutedEventArgs e)
        {
            var nctx = (ContextMenu)FindResource("ctxMainMenu");
            if (nctx.Placement != PlacementMode.Bottom || nctx.PlacementTarget != BtnMenu)
            {
                nctx.Placement = PlacementMode.Bottom;
                nctx.PlacementTarget = BtnMenu;
            }
            nctx.IsOpen = true;
        }

        private void ctxMainMenu_LayoutUpdated(object sender, EventArgs e)
        {
            var ctx = (ContextMenu)FindResource("ctxMainMenu");
            if (ctx.HorizontalOffset != 0) return;
            ctx.HorizontalOffset = BtnMenu.ActualWidth - ctx.ActualWidth;
        }

        private void menuExit_Click(object sender, RoutedEventArgs e)
        {
            Environment.Exit(0);
        }

        private void menuLanguage_Click(object sender, RoutedEventArgs e)
        {
            var langDlg = new LanguageSettingsWindow
            {
                Owner = this
            };
            langDlg.ShowDialog(this);
        }

        private void BtnQueue_Click(object sender, RoutedEventArgs e)
        {
            this.SchedulerClicked?.Invoke(sender, e);
        }

        private void menuSettings_Click(object sender, RoutedEventArgs e)
        {
            this.SettingsClicked?.Invoke(this, e);
        }

        private void BtnMonitoring_Click(object sender, RoutedEventArgs e)
        {
            BrowserMonitoringButtonClicked?.Invoke(sender, e);
        }

        private void menuClearFinished_Click(object sender, RoutedEventArgs e)
        {
            this.ClearAllFinishedClicked?.Invoke(sender, e);
        }

        private void menuBrowserMonitor_Click(object sender, RoutedEventArgs e)
        {
            BrowserMonitoringSettingsClicked?.Invoke(sender, e);
        }

        private void menuImport_Click(object sender, RoutedEventArgs e)
        {
            ImportClicked?.Invoke(sender, e);
        }

        private void menuExport_Click(object sender, RoutedEventArgs e)
        {
            ExportClicked?.Invoke(sender, e);
        }

        private void menuHelpAndSupport_Click(object sender, RoutedEventArgs e)
        {
            SupportPageClicked?.Invoke(sender, e);
        }

        private void menuReportProblem_Click(object sender, RoutedEventArgs e)
        {
            BugReportClicked?.Invoke(sender, e);
        }

        private void menuCheckForUpdate_Click(object sender, RoutedEventArgs e)
        {
            UpdateClicked?.Invoke(sender, e);
        }

        private void menuAbout_Click(object sender, RoutedEventArgs e)
        {
            var win = new AboutWindow
            {
                Owner = this
            };
            win.ShowDialog(this);
        }

        private void lvInProgress_Click(object sender, RoutedEventArgs e)
        {
            if (e.OriginalSource is GridViewColumnHeader column)
            {
                string sortBy = (string)column.Tag;
                if (string.IsNullOrEmpty(sortBy))
                {
                    return;
                }
                if (inProgressListViewSortCol != null)
                {
                    AdornerLayer.GetAdornerLayer(inProgressListViewSortCol).Remove(inProgressListViewSortAdorner);
                    lvInProgress.Items.SortDescriptions.Clear();
                }

                ListSortDirection newDir = ListSortDirection.Ascending;
                if (inProgressListViewSortCol == column && inProgressListViewSortAdorner.Direction == newDir)
                    newDir = ListSortDirection.Descending;

                inProgressListViewSortCol = column;
                inProgressListViewSortAdorner = new SortAdorner(inProgressListViewSortCol, newDir);
                AdornerLayer.GetAdornerLayer(inProgressListViewSortCol).Add(inProgressListViewSortAdorner);
                lvInProgress.Items.SortDescriptions.Add(new SortDescription(sortBy, newDir));
            }
        }

        private void CreateMenuItems()
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

            var lvInProgressContextMenu = (ContextMenu)this.FindResource("lvInProgressContextMenu");
            var lvFinishedContextMenu = (ContextMenu)this.FindResource("lvFinishedContextMenu");
            var i = 0;
            foreach (MenuItemWrapper mi in menuItems)
            {
                if (i < 10)
                {
                    lvInProgressContextMenu.Items.Add(mi.Menu);
                }
                else
                {
                    lvFinishedContextMenu.Items.Add(mi.Menu);
                }
                i++;
            }
            lvInProgress.ContextMenuOpening += LvInProgressContextMenu_ContextMenuOpening;
            lvFinished.ContextMenuOpening += LvFinishedContextMenu_ContextMenuOpening;

            var newDownloadMenu = (ContextMenu)FindResource("newDownloadContextMenu");

            var menuNewDownload = (MenuItem)newDownloadMenu.Items[0];
            menuNewDownload.Click += MenuNewDownload_Click;
            menuNewDownload.Header = TextResource.GetText("LBL_NEW_DOWNLOAD");

            var menuVideoDownload = (MenuItem)newDownloadMenu.Items[1];
            menuVideoDownload.Click += MenuVideoDownload_Click;
            menuVideoDownload.Header = TextResource.GetText("LBL_VIDEO_DOWNLOAD");

            var menuBatchDownload = (MenuItem)newDownloadMenu.Items[2];
            menuBatchDownload.Click += MenuBatchDownload_Click;
            menuBatchDownload.Header = TextResource.GetText("MENU_BATCH_DOWNLOAD");
        }

        private void BtnHelp_Click(object sender, RoutedEventArgs e)
        {
            if (BtnHelp.Tag != null)
            {
                UpdateClicked?.Invoke(sender, e);
            }
            else
            {
                HelpClicked?.Invoke(sender, e);
            }
        }

        private void Window_Closing(object sender, CancelEventArgs e)
        {
            e.Cancel = true;
            this.Hide();
        }

        private void ListViewItem_MouseDoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
        {
            DownloadListDoubleClicked?.Invoke(sender, e);
        }

        private void MenuNewDownload_Click(object sender, RoutedEventArgs e)
        {
            this.NewDownloadClicked?.Invoke(sender, e);
        }

        private void MenuVideoDownload_Click(object sender, RoutedEventArgs e)
        {
            this.YoutubeDLDownloadClicked?.Invoke(sender, e);
        }

        private void MenuBatchDownload_Click(object sender, RoutedEventArgs e)
        {
            this.BatchDownloadClicked?.Invoke(sender, e);
        }

        private void LvFinishedContextMenu_ContextMenuOpening(object sender, ContextMenuEventArgs e)
        {
            this.FinishedContextMenuOpening?.Invoke(sender, e);
        }

        private void LvInProgressContextMenu_ContextMenuOpening(object sender, ContextMenuEventArgs e)
        {
            this.InProgressContextMenuOpening?.Invoke(sender, e);
        }

        public void ShowDownloadSelectionWindow(IApp app, IAppUI appUI, FileNameFetchMode mode, IEnumerable<object> downloads)
        {
            var window = new DownloadSelectionWindow(app, appUI, FileNameFetchMode.FileNameAndExtension, downloads);
            window.Show();
        }

        public IClipboardMonitor GetClipboardMonitor() => this.clipboarMonitor;
    }

    internal class DummyButton : IButton
    {
        public bool Visible { get => true; set { } }
        public bool Enable { get => true; set { } }

        public event EventHandler Clicked;
    }
}
