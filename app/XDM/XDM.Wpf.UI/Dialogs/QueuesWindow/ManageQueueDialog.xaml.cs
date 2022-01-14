using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;
using XDM.Wpf.UI.Common;
using XDM.Wpf.UI.Win32;
using XDMApp;

namespace XDM.Wpf.UI.Dialogs.QueuesWindow
{
    /// <summary>
    /// Interaction logic for ManageQueueDialog.xaml
    /// </summary>
    public partial class ManageQueueDialog : Window, IDialog, IQueuesWindow
    {
        private IAppUI appUI;
        private DownloadSchedule defaultSchedule;

        public event EventHandler<QueueListEventArgs>? QueuesModified;
        public event EventHandler<DownloadListEventArgs>? QueueStartRequested;
        public event EventHandler<DownloadListEventArgs>? QueueStopRequested;
        public event EventHandler? WindowClosing;

        private readonly ObservableCollection<DownloadQueue> queues = new();
        private readonly ObservableCollection<InProgressDownloadEntry> downloads = new();

        public ManageQueueDialog(IAppUI appUI)
        {
            InitializeComponent();

            this.appUI = appUI;

            this.defaultSchedule = new DownloadSchedule
            {
                StartTime = DateTime.Now.TimeOfDay,
                EndTime = DateTime.Now.Date.AddHours(23).AddMinutes(59).TimeOfDay
            };

            this.SchedulerPanel.Schedule = defaultSchedule;

            this.SchedulerPanel.ValueChanged += (_, _) =>
            {
                if (ChkEnableScheduler.IsChecked.HasValue && ChkEnableScheduler.IsChecked.Value)
                {
                    if (LbQueues.SelectedItem is DownloadQueue queue)
                    {
                        queue.Schedule = this.SchedulerPanel.Schedule;
                    }
                }
            };

            this.LbQueues.ItemsSource = queues;

            this.lvFiles.ItemsSource = downloads;
            this.lvFiles.SelectionChanged += (_, _) => ListSelectionChanged();

            this.ChkEnableScheduler.Checked += (_, _) =>
            {
                SchedulerPanel.IsEnabled = true;
                if (LbQueues.SelectedItem is DownloadQueue queue)
                {
                    queue.Schedule = this.SchedulerPanel.Schedule;
                }
            };

            this.ChkEnableScheduler.Unchecked += (_, _) =>
            {
                SchedulerPanel.IsEnabled = false;
                if (LbQueues.SelectedItem is DownloadQueue queue)
                {
                    queue.Schedule = null;
                }
            };

            Closing += (_, _) =>
                  this.WindowClosing?.Invoke(this, EventArgs.Empty);
        }

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            NativeMethods.DisableMinMaxButton(this);
        }

        public bool Result { get; set; } = false;

        private void BtnNew_Click(object sender, RoutedEventArgs e)
        {
            new NewQueueWindow(this.appUI, (queue, newQueue) =>
            {
                this.queues.Add(queue);
            }, null)
            { Owner = this }.ShowDialog(this);
        }

        public void RefreshView()
        {
            if (LbQueues.SelectedItem is DownloadQueue queue)
            {
                ClearCollection<InProgressDownloadEntry>(this.downloads);
                var realQueue = QueueManager.GetQueue(queue.ID);

                if (realQueue != null)
                {
                    foreach (var id in realQueue.DownloadIds)
                    {
                        var entry = appUI.GetInProgressDownloadEntry(id);
                        if (entry != null)
                        {
                            this.downloads.Add(entry);
                        }
                    }
                }
            }
        }

        private void ListSelectionChanged()
        {
            BtnAdd.IsEnabled = BtnRemove.IsEnabled = BtnUp.IsEnabled = BtnDown.IsEnabled = lvFiles.SelectedItems.Count > 0;
        }

        public void SetData(IEnumerable<DownloadQueue> queues)
        {
            ClearCollection(this.queues);
            foreach (var item in queues)
            {
                this.queues.Add(item);
            }
        }

        public void ShowWindow(object peer)
        {
            this.Owner = (Window)peer;
            NativeMethods.ShowDialog(this, (Window)peer);
        }

        private void LoadQueueDetails(DownloadQueue queue)
        {
            lvFiles.UnselectAll();
            ClearCollection(this.downloads);
            foreach (var id in queue.DownloadIds)
            {
                var ent = appUI.GetInProgressDownloadEntry(id);
                if (ent != null)
                {
                    this.downloads.Add(ent);
                }
            }
            lvFiles.ItemsSource = this.downloads;
            ChkEnableScheduler.IsChecked = queue.Schedule.HasValue;
            if (queue.Schedule.HasValue)
            {
                this.SchedulerPanel.Schedule = queue.Schedule.Value;
            }
            else
            {
                this.SchedulerPanel.Schedule = this.defaultSchedule;
            }
            this.SchedulerPanel.Schedule = queue.Schedule ?? default;
        }

        private void EnableControls(bool enable)
        {
            this.Tab.IsEnabled = enable;
            this.BtnNew.IsEnabled = this.BtnDel.IsEnabled = this.BtnStart.IsEnabled = this.BtnStop.IsEnabled = enable;
        }

        private void UpdateControls(DownloadQueue? queue)
        {
            if (queue != null)
            {
                LoadQueueDetails(queue);
                EnableControls(true);
            }
            else
            {
                this.SchedulerPanel.Schedule = defaultSchedule;
                EnableControls(false);
            }
        }

        private void ClearCollection<T>(ObservableCollection<T> collection)
        {
            for (int i = collection.Count - 1; i >= 0; i--)
            {
                collection.RemoveAt(i);
            }
        }

        private void BtnSave_Click(object sender, RoutedEventArgs e)
        {
            SaveQueues();
            Close();
        }

        private void SaveQueues()
        {
            QueuesModified?.Invoke(this, new QueueListEventArgs(this.queues.Select(x => x).ToList()));
        }

        private void BtnStart_Click(object sender, RoutedEventArgs e)
        {
            SaveQueues();
            var queue = (DownloadQueue)LbQueues.SelectedItem;
            QueueStartRequested?.Invoke(this, new DownloadListEventArgs(queue.DownloadIds));
        }

        private void BtnStop_Click(object sender, RoutedEventArgs e)
        {
            SaveQueues();
            var queue = (DownloadQueue)LbQueues.SelectedItem;
            QueueStopRequested?.Invoke(this, new DownloadListEventArgs(queue.DownloadIds));
        }

        private void BtnDel_Click(object sender, RoutedEventArgs e)
        {
            if (LbQueues.SelectedIndex >= 0)
            {
                this.queues.RemoveAt(LbQueues.SelectedIndex);
            }
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            Close();
        }

        private void BtnAdd_Click(object sender, RoutedEventArgs e)
        {
            if (LbQueues.SelectedIndex < 0) return;
            var newQueueDialog = new NewQueueWindow(appUI, (queue, newQueue) =>
            {
                LoadQueueDetails(queue);
            }, (DownloadQueue)LbQueues.SelectedItem)
            { Owner = this }.ShowDialog(this);
        }

        private void BtnRemove_Click(object sender, RoutedEventArgs e)
        {
            var selectedQueue = (DownloadQueue)LbQueues.SelectedItem;
            if (selectedQueue == null) return;
            foreach (InProgressDownloadEntry entry in this.downloads)
            {
                selectedQueue.DownloadIds.Remove(entry.Id);
            }
            LoadQueueDetails(selectedQueue);
        }

        private void BtnUp_Click(object sender, RoutedEventArgs e)
        {
            //if (listView1.SelectedIndices.Count > 0 && listView1.SelectedIndices[0] > 0)
            //{
            //    var lvi = listView1.Items[listView1.SelectedIndices[0] - 1];
            //    listView1.Items.Remove(lvi);
            //    listView1.Items.Insert(listView1.SelectedIndices[listView1.SelectedIndices.Count - 1] + 1, lvi);
            //}
            if (lvFiles.SelectedItems.Count > 0 && lvFiles.SelectedIndex > 0)
            {
                var index1 = lvFiles.SelectedIndex - 1;
                var index2 = lvFiles.SelectedIndex + lvFiles.SelectedItems.Count;
                var value = this.downloads[index1];
                this.downloads.RemoveAt(index1);
                this.downloads.Insert(index2, value);
            }
        }

        private void BtnDown_Click(object sender, RoutedEventArgs e)
        {
            if (lvFiles.SelectedItems.Count > 0 && lvFiles.SelectedItems.Count + lvFiles.SelectedIndex < this.downloads.Count)
            {
                var item = this.downloads[lvFiles.SelectedItems.Count + lvFiles.SelectedIndex];
                this.downloads.RemoveAt(lvFiles.SelectedItems.Count + lvFiles.SelectedIndex);
                this.downloads.Insert(lvFiles.SelectedIndex, item);
            }
            //if (listView1.SelectedIndices.Count > 0 && listView1.SelectedIndices[listView1.SelectedIndices.Count - 1] < listView1.Items.Count - 1)
            //{
            //    var lvi = listView1.Items[listView1.SelectedIndices[listView1.SelectedIndices.Count - 1] + 1];
            //    listView1.Items.Remove(lvi);
            //    listView1.Items.Insert(listView1.SelectedIndices[0], lvi);
            //}
        }

        private void BtnMoveTo_Click(object sender, RoutedEventArgs e)
        {
            if (lvFiles.SelectedItems.Count > 0 && queues.Count > 1)
            {
                var qsd = new QueueSelectionWindow() { Owner = this };
                var queues1 = new List<string>(this.queues.Count);
                var selectedItems = LbQueues.SelectedItems;
                foreach (DownloadQueue item in queues)
                {
                    var found = false;
                    foreach (var si in selectedItems)
                    {
                        if (si == item)
                        {
                            found = true;
                            break;
                        }
                    }
                    if (!found)
                    {
                        queues1.Add(item.Name);
                    }
                }
                var downloadIds = new string[this.lvFiles.SelectedItems.Count];
                var index = 0;
                foreach (InProgressDownloadEntry lvi in this.lvFiles.SelectedItems)
                {
                    downloadIds[index++] = lvi.Id;
                }
                qsd.SetData(queues1, downloadIds);
                qsd.QueueSelected += QueueSelectionDialog_QueueSelected;
                qsd.ShowDialog(this);
            }
        }

        private void QueueSelectionDialog_QueueSelected(object sender, QueueSelectionEventArgs e)
        {
            var queue = (DownloadQueue)queues[e.SelectedQueueIndex];
            var downloadIds = e.DownloadIds;
            var selectedQueue = (DownloadQueue)LbQueues.SelectedItem;
            foreach (var id in downloadIds)
            {
                selectedQueue.DownloadIds.Remove(id);
                queue.DownloadIds.Add(id);
            }
            LoadQueueDetails(selectedQueue);
        }
    }
}
