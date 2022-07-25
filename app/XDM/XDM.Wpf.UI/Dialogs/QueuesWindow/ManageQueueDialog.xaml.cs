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
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using TraceLog;
using XDM.Core;
using XDM.Core.UI;
using XDM.Wpf.UI.Common;
using XDM.Wpf.UI.Common.Helpers;
using XDM.Wpf.UI.Win32;
using XDM.Core;

namespace XDM.Wpf.UI.Dialogs.QueuesWindow
{
    /// <summary>
    /// Interaction logic for ManageQueueDialog.xaml
    /// </summary>
    public partial class ManageQueueDialog : Window, IDialog, IQueuesWindow
    {
        private IUIService appUI;
        private DownloadSchedule defaultSchedule;

        public event EventHandler<QueueListEventArgs>? QueuesModified;
        public event EventHandler<DownloadListEventArgs>? QueueStartRequested;
        public event EventHandler<DownloadListEventArgs>? QueueStopRequested;
        public event EventHandler? WindowClosing;

        private readonly ObservableCollection<DownloadQueue> queues = new();
        private readonly ObservableCollection<InProgressDownloadEntry> downloads = new();

        public ManageQueueDialog(IUIService appUI)
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
                DownloadSchedule? schedule = null;
                if (ChkEnableScheduler.IsChecked.HasValue && ChkEnableScheduler.IsChecked.Value)
                {
                    schedule = this.SchedulerPanel.Schedule;
                }
                if (LbQueues.SelectedItem is DownloadQueue queue)
                {
                    queue.Schedule = schedule;
                }
            };

            this.LbQueues.ItemsSource = queues;
            this.LbQueues.SelectionChanged += (_, _) =>
            {
                var selected = (DownloadQueue)this.LbQueues.SelectedItem;
                if (selected != null)
                {
                    UpdateControls(selected);
                }
            };

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

#if NET45_OR_GREATER
            if (XDM.Wpf.UI.App.Skin == Skin.Dark)
            {
                var helper = new WindowInteropHelper(this);
                helper.EnsureHandle();
                DarkModeHelper.UseImmersiveDarkMode(helper.Handle, true);
            }
#endif
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
            BtnRemove.IsEnabled = BtnUp.IsEnabled = BtnDown.IsEnabled = BtnMoveTo.IsEnabled = lvFiles.SelectedItems.Count > 0;
        }

        public void SetData(IEnumerable<DownloadQueue> queues)
        {
            ClearCollection(this.queues);
            foreach (var item in queues)
            {
                this.queues.Add(item);
            }
            if (this.queues.Count > 0)
            {
                this.LbQueues.SelectedIndex = 0;
            }
            ListSelectionChanged();
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
            if (queue.Schedule.HasValue)
            {
                this.SchedulerPanel.Schedule = queue.Schedule.Value;
            }
            else
            {
                this.SchedulerPanel.Schedule = this.defaultSchedule;
            }
            ChkEnableScheduler.IsChecked = queue.Schedule.HasValue;
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
            var selectedIds = new List<string>(lvFiles.SelectedItems?.Count ?? 0);
            foreach (InProgressDownloadEntry item in lvFiles.SelectedItems!)
            {
                selectedIds.Add(item.Id);
            }
            foreach (var id in selectedIds)
            {
                selectedQueue.DownloadIds.Remove(id);
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
            var indices = lvFiles.GetSelectedIndices();
            if (indices.Length > 0 && indices[0] > 0)
            {
                var item = this.downloads[indices[0] - 1];
                var index = indices[indices.Length - 1];
                this.downloads.Remove(item);
                this.downloads.Insert(index, item);
                //var index1 = indices[0] - 1;
                //var index2 = indices[0] + lvFiles.SelectedItems.Count - 1;
                //var value = this.downloads[index1];
                //this.downloads.RemoveAt(index1);
                //this.downloads.Insert(index2, value);
            }
        }

        private void BtnDown_Click(object sender, RoutedEventArgs e)
        {
            var indices = lvFiles.GetSelectedIndices();
            if (indices.Length > 0 && indices[indices.Length - 1] < this.downloads.Count - 1)
            {
                var item = this.downloads[indices[indices.Length - 1] + 1];
                this.downloads.Remove(item);
                this.downloads.Insert(indices[0], item);
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
            if (lvFiles.SelectedItems.Count > 0 && queues.Count > 1 && LbQueues.SelectedItems.Count > 0)
            {
                var qsd = new QueueSelectionWindow() { Owner = this };
                var queueNames = new List<string>();
                var queueIds = new List<string>();
                var selectedItem = (DownloadQueue)LbQueues.SelectedItems[0];
                foreach (DownloadQueue item in queues)
                {
                    if (item != selectedItem)
                    {
                        queueNames.Add(item.Name);
                        queueIds.Add(item.ID);
                    }
                }
                var downloadIds = new List<string>();
                foreach (InProgressDownloadEntry lvi in this.lvFiles.SelectedItems)
                {
                    downloadIds.Add(lvi.Id);
                }
                qsd.SetData(queueNames, queueIds, downloadIds);
                qsd.QueueSelected += QueueSelectionDialog_QueueSelected;
                qsd.ShowDialog(this);
            }
        }

        private void QueueSelectionDialog_QueueSelected(object sender, QueueSelectionEventArgs e)
        {
            var qid = e.SelectedQueueId;
            var queue = this.queues.First(x => x.ID == qid);/// (DownloadQueue)queues[e.SelectedQueueIndex];
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
