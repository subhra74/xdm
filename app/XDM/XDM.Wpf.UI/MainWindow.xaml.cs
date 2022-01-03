using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
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
        private IButton button = new DummyButton();

        public MainWindow()
        {
            InitializeComponent();
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
        }

        private void lvCategory_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            var index = lvCategory.SelectedIndex;
            if (index == 0)
            {
                lvInProgress.Visibility = Visibility.Visible;
                lvFinished.Visibility = Visibility.Collapsed;
            }
            else if (index > 0)
            {
                lvInProgress.Visibility = Visibility.Collapsed;
                lvFinished.Visibility = Visibility.Visible;

                if (index > 1)
                {
                    var cat = lvCategory.SelectedItem;
                    ListCollectionView view = (ListCollectionView)
                        CollectionViewSource.GetDefaultView(lvFinished.ItemsSource);
                    view.Filter = a => true;
                }
            }
        }

        private bool IsCategoryMatched(FinishedDownloadEntryWrapper entry, CategoryWrapper category)
        {
            return Helpers.IsOfCategoryOrMatchesKeyword(entry.Name, null, category.category);
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

        public IButton NewButton => button;

        public IButton DeleteButton => button;

        public IButton PauseButton => button;

        public IButton ResumeButton => button;

        public IButton OpenFileButton => button;

        public IButton OpenFolderButton => button;

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
    }

    internal class DummyButton : IButton
    {
        public bool Visible { get => true; set { } }
        public bool Enable { get => true; set { } }

        public event EventHandler Clicked;
    }
}
