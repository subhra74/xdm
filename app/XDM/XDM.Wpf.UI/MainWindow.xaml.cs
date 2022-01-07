using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Navigation;
using System.Windows.Shapes;
using System.Windows.Threading;
using XDM.Common.UI;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;
using XDM.Core.Lib.Util;

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
        private GridViewColumnHeader _lastHeaderClicked = null;
        private ListSortDirection _lastDirection = ListSortDirection.Ascending;

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

            SwitchToFinishedView();
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

        public IEnumerable<FinishedDownloadEntry> FinishedDownloads
        {
            get => this.finishedList.Select(x => x.DownloadEntry);
            set
            {
                this.finishedList = new ObservableCollection<FinishedDownloadEntryWrapper>(
                    value.Select(x => new FinishedDownloadEntryWrapper(x)));
                this.lvFinished.ItemsSource = finishedList;
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

        public IMenuItem[] MenuItems => new IMenuItem[0];

        public Dictionary<string, IMenuItem> MenuItemMap => throw new NotImplementedException();

        public IInProgressDownloadRow FindInProgressItem(string id) =>
            this.lvInProgress.Items.OfType<IInProgressDownloadRow>()
            .Where(x => x.DownloadEntry.Id == id).FirstOrDefault();

        public IFinishedDownloadRow FindFinishedItem(string id) =>
            this.lvFinished.Items.OfType<IFinishedDownloadRow>()
            .Where(x => x.DownloadEntry.Id == id).FirstOrDefault();

        public void AddToTop(InProgressDownloadEntry entry)
        {
            this.lvInProgress.Items.Add(entry);
        }

        public void AddToTop(FinishedDownloadEntry entry)
        {
            this.lvFinished.Items.Add(entry);
        }

        public void SwitchToInProgressView()
        {
            lvInProgress.SelectedIndex = 0;
        }

        public void ClearInProgressViewSelection()
        {
            lvInProgress.SelectedIndex = -1;
        }

        public void SwitchToFinishedView()
        {
            lvCategory.SelectedIndex = 1;
        }

        public void ClearFinishedViewSelection()
        {
            lvFinished.SelectedIndex = -1;
        }

        public bool Confirm(object window, string text)
        {
            throw new NotImplementedException();
        }

        public void ConfirmDelete(string text, out bool approved, out bool deleteFiles)
        {
            throw new NotImplementedException();
        }

        public IDownloadCompleteDialog CreateDownloadCompleteDialog(IApp app)
        {
            throw new NotImplementedException();
        }

        public INewDownloadDialogSkeleton CreateNewDownloadDialog(bool empty)
        {
            throw new NotImplementedException();
        }

        public INewVideoDownloadDialog CreateNewVideoDialog()
        {
            throw new NotImplementedException();
        }

        public IProgressWindow CreateProgressWindow(string downloadId, IApp app, IAppUI appUI)
        {
            throw new NotImplementedException();
        }

        public void RunOnUIThread(Action action)
        {
            Dispatcher.Invoke(action);
        }

        public void RunOnUIThread(Action<string, int, double, long> action, string id, int progress, double speed, long eta)
        {
            Dispatcher.Invoke(action, id, progress, speed, eta);
        }

        public void Delete(IInProgressDownloadRow row)
        {
            throw new NotImplementedException();
        }

        public void Delete(IFinishedDownloadRow row)
        {
            throw new NotImplementedException();
        }

        public void DeleteAllFinishedDownloads()
        {
            throw new NotImplementedException();
        }

        public void Delete(IEnumerable<IInProgressDownloadRow> rows)
        {
            throw new NotImplementedException();
        }

        public void Delete(IEnumerable<IFinishedDownloadRow> rows)
        {
            throw new NotImplementedException();
        }

        public string GetUrlFromClipboard()
        {
            throw new NotImplementedException();
        }

        public AuthenticationInfo? PromtForCredentials(string message)
        {
            throw new NotImplementedException();
        }

        public void ShowUpdateAvailableNotification()
        {
            throw new NotImplementedException();
        }

        public void ShowMessageBox(object window, string message)
        {
            throw new NotImplementedException();
        }

        public void OpenNewDownloadMenu()
        {
            throw new NotImplementedException();
        }

        public string SaveFileDialog(string initialPath)
        {
            throw new NotImplementedException();
        }

        public void ShowRefreshLinkDialog(InProgressDownloadEntry entry, IApp app)
        {
            throw new NotImplementedException();
        }

        public void SetClipboardText(string text)
        {
            throw new NotImplementedException();
        }

        public void SetClipboardFile(string file)
        {
            throw new NotImplementedException();
        }

        public void ShowPropertiesDialog(BaseDownloadEntry ent, ShortState state)
        {
            throw new NotImplementedException();
        }

        public void ShowYoutubeDLDialog(IAppUI appUI, IApp app)
        {
            throw new NotImplementedException();
        }

        public DownloadSchedule? ShowSchedulerDialog(DownloadSchedule schedule)
        {
            throw new NotImplementedException();
        }

        public void ShowBatchDownloadWindow(IApp app, IAppUI appUi)
        {
            throw new NotImplementedException();
        }

        public void ShowSettingsDialog(IApp app, int page = 0)
        {
            throw new NotImplementedException();
        }

        public void ImportDownloads(IApp app)
        {
            throw new NotImplementedException();
        }

        public void ExportDownloads(IApp app)
        {
            throw new NotImplementedException();
        }

        public void UpdateBrowserMonitorButton()
        {
            throw new NotImplementedException();
        }

        public void ShowBrowserMonitoringDialog(IApp app)
        {
            throw new NotImplementedException();
        }

        public void UpdateParallalismLabel()
        {
            throw new NotImplementedException();
        }

        public IUpdaterUI CreateUpdateUIDialog(IAppUI ui)
        {
            throw new NotImplementedException();
        }

        private void SearchButton_Click(object sender, RoutedEventArgs e)
        {
            ApplyFilter();
        }

        private void lvFinished_Click(object sender, RoutedEventArgs e)
        {

        }

        public void ClearUpdateInformation()
        {
            throw new NotImplementedException();
        }

        public IQueuesWindow CreateQueuesAndSchedulerWindow(IAppUI appUi)
        {
            throw new NotImplementedException();
        }

        public IQueueSelectionDialog CreateQueueSelectionDialog()
        {
            throw new NotImplementedException();
        }

        internal bool BrowserMonitoringEnabled => Config.Instance.IsBrowserMonitoringEnabled;

#if NET45_OR_GREATER
        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);

            if (App.Skin == Skin.Dark)
            {
                var helper = new WindowInteropHelper(this);
                helper.EnsureHandle();
                DarkModeHelper.UseImmersiveDarkMode(helper.Handle, true);
            }
        }
#endif

    }

    internal class DummyButton : IButton
    {
        public bool Visible { get => true; set { } }
        public bool Enable { get => true; set { } }

        public event EventHandler Clicked;
    }

    internal class ButtonWrapper : IButton
    {
        private Button button;

        public ButtonWrapper(Button button)
        {
            this.button = button;
            this.button.Click += Button_Click;
        }

        private void Button_Click(object sender, RoutedEventArgs e)
        {
            Clicked?.Invoke(sender, e);
        }

        public bool Visible
        {
            get => button.Visibility == Visibility.Visible;
            set => button.Visibility = value ? Visibility.Visible : Visibility.Collapsed;
        }

        public bool Enable
        {
            get => button.IsEnabled;
            set => button.IsEnabled = value;
        }

        public event EventHandler? Clicked;
    }
}
