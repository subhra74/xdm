using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Windows;
using System.Windows.Interop;
using Translations;
using XDM.Core;
using XDM.Core.Util;
using XDM.Wpf.UI.Common;
using XDM.Wpf.UI.Win32;
using XDM.Core;

namespace XDM.Wpf.UI.Dialogs.QueuesWindow
{
    /// <summary>
    /// Interaction logic for NewQueueWindow.xaml
    /// </summary>
    public partial class NewQueueWindow : Window, IDialog
    {
        private ObservableCollection<InProgressDownloadEntryWrapper> list;
        private DownloadQueue? modifyingQueue;
        private Action<DownloadQueue, bool> okAction;

        public NewQueueWindow(Action<DownloadQueue, bool> okAction,
            DownloadQueue? modifyingQueue)
        {
            InitializeComponent();

            this.okAction = okAction;
            if (modifyingQueue == null)
            {
                this.TxtQueueName.Text = "New queue #" + QueueManager.QueueAutoNumber;
                QueueManager.QueueAutoNumber++;
            }
            else
            {
                this.TxtQueueName.Text = modifyingQueue.Name;
                this.modifyingQueue = modifyingQueue;
            }

            var set = new HashSet<string>();
            foreach (var queue in QueueManager.Queues)
            {
                foreach (var id in queue.DownloadIds)
                {
                    set.Add(id);
                }
            }

            var list = new ObservableCollection<InProgressDownloadEntryWrapper>();
            foreach (var ent in ApplicationContext.Application.GetAllInProgressDownloads())
            {
                if (!set.Contains(ent.Id))
                {
                    list.Add(new InProgressDownloadEntryWrapper(ent));
                }
            }

            this.list = list;
            lvDownloads.ItemsSource = this.list;
        }

        private bool OnApproved()
        {
            if (string.IsNullOrEmpty(TxtQueueName.Text))
            {
                MessageBox.Show(this, TextResource.GetText("MSG_QUEUE_NAME_MISSING"));
                return false;
            }
            var list2 = new List<string>(this.list.Count);
            foreach (var entry in list)
            {
                if (entry.IsSelected)
                {
                    list2.Add(entry.Id);
                }
            }
            if (modifyingQueue == null)
            {
                okAction.Invoke(new DownloadQueue(Guid.NewGuid().ToString(), TxtQueueName.Text) { DownloadIds = list2 }, true);
            }
            else
            {
                modifyingQueue.DownloadIds.AddRange(list2);
                okAction.Invoke(modifyingQueue, false);
            }
            return true;
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

        private void BtnOK_Click(object sender, RoutedEventArgs e)
        {
            if (OnApproved())
            {
                Close();
            }
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            Close();
        }

        private void ChkSelectAll_Checked(object sender, RoutedEventArgs e)
        {
            foreach (var ent in this.list)
            {
                if (ChkSelectAll.IsChecked.HasValue)
                {
                    ent.IsSelected = ChkSelectAll.IsChecked.Value;
                }
                else
                {
                    ent.IsSelected = false;
                }
            }
        }
    }

    internal class InProgressDownloadEntryWrapper : INotifyPropertyChanged
    {
        private InProgressDownloadEntry entry;
        private bool selected;

        internal InProgressDownloadEntryWrapper(InProgressDownloadEntry entry)
        {
            this.entry = entry;
        }

        public bool IsSelected
        {
            get => selected;
            set
            {
                selected = value;
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs("IsSelected"));
            }
        }
        public string Name => entry.Name;
        public string Size => FormattingHelper.FormatSize(entry.Size);
        public string DateAdded => entry.DateAdded.ToShortDateString() + " " + entry.DateAdded.ToShortTimeString();
        public string Status => entry.Status.ToString();
        public string Id => entry.Id;

        public event PropertyChangedEventHandler? PropertyChanged;
    }
}
