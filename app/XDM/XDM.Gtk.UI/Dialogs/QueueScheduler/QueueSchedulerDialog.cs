using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

using Gtk;
using System.IO;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Core;
using XDM.Core.UI;
using XDM.GtkUI.Utils;
using Translations;
using UI = Gtk.Builder.ObjectAttribute;
using XDM.Core.Util;
using XDM.Core;
using XDM.GtkUI.Dialogs.NewQueue;

namespace XDM.GtkUI.Dialogs.QueueScheduler
{
    internal class QueueSchedulerDialog : Dialog, IQueuesWindow
    {
        [UI] private Button BtnNew = null;
        [UI] private Button BtnDel = null;
        [UI] private Button BtnStart = null;
        [UI] private Button BtnStop = null;
        [UI] private Button BtnSave = null;
        [UI] private Button BtnCancel = null;
        [UI] private Button BtnAdd = null;
        [UI] private Button BtnRemove = null;
        [UI] private Button BtnUp = null;
        [UI] private Button BtnDown = null;
        [UI] private Button BtnMoveTo = null;
        [UI] private TreeView lvFiles = null;
        [UI] private TreeView LbQueues = null;
        [UI] private CheckButton ChkEnableScheduler = null;
        [UI] private CheckButton chkEveryday = null;
        [UI] private CheckButton chkSun = null;
        [UI] private CheckButton chkMon = null;
        [UI] private CheckButton chkTue = null;
        [UI] private CheckButton chkWed = null;
        [UI] private CheckButton chkThu = null;
        [UI] private CheckButton chkFri = null;
        [UI] private CheckButton chkSat = null;
        [UI] private Label LblQueueStart = null;
        [UI] private Label LblQueueStop = null;
        [UI] private Label TabHeader1 = null;
        [UI] private Label TabHeader2 = null;
        [UI] private ComboBox CmbHour1 = null;
        [UI] private ComboBox CmbMinute1 = null;
        [UI] private ComboBox CmbAmPm1 = null;
        [UI] private ComboBox CmbHour2 = null;
        [UI] private ComboBox CmbMinute2 = null;
        [UI] private ComboBox CmbAmPm2 = null;
        [UI] private ScrolledWindow queueScroll = null;
        [UI] private Notebook Tab = null;

        private WindowGroup group;
        private ListStore queueListStore;
        private ListStore filesListStore;
        private bool suppressEvent;
        private byte[] bits;
        private readonly CheckButton[] checkboxes;
        private DownloadSchedule defaultSchedule;
        private SchedulerPanelControl SchedulerPanel;

        public event EventHandler<QueueListEventArgs>? QueuesModified;
        public event EventHandler<DownloadListEventArgs>? QueueStartRequested;
        public event EventHandler<DownloadListEventArgs>? QueueStopRequested;
        public event EventHandler? WindowClosing;

        private QueueSchedulerDialog(Builder builder, Window parent, WindowGroup group) : base(builder.GetRawOwnedObject("dialog"))
        {
            builder.Autoconnect(this);
            Modal = true;
            SetPosition(WindowPosition.CenterAlways);
            TransientFor = parent;
            this.group = group;
            this.group.AddWindow(this);

            GtkHelper.AttachSafeDispose(this);
            Title = TextResource.GetText("DESC_Q_TITLE");
            SetDefaultSize(700, 500);
            LoadTexts();
            queueListStore = new ListStore(typeof(string), typeof(DownloadQueue));
            LbQueues.Model = queueListStore;

            var queueNameRendererText = new CellRendererText();
            var queueNameColumn = new TreeViewColumn("", queueNameRendererText, "text", 0)
            {
                Resizable = false,
                Reorderable = false,
                Sizing = TreeViewColumnSizing.Autosize,
                Expand = true
            };
            LbQueues.HeadersVisible = false;
            LbQueues.AppendColumn(queueNameColumn);
            queueScroll.SetSizeRequest(150, 100);

            filesListStore = new ListStore(typeof(string), typeof(string), typeof(string), typeof(InProgressDownloadEntry));
            lvFiles.Model = filesListStore;

            var k = 0;
            foreach (var key in new string[] { "ND_FILE", "SORT_SIZE", "SORT_STATUS" })
            {
                var cellRendererText = new CellRendererText();
                var treeViewColumn = new TreeViewColumn(TextResource.GetText(key), cellRendererText, "text", k++)
                {
                    Resizable = true,
                    Reorderable = false,
                    Sizing = TreeViewColumnSizing.Fixed,
                    FixedWidth = 150
                };
                lvFiles.AppendColumn(treeViewColumn);
            }

            this.SchedulerPanel = new SchedulerPanelControl(
                chkEveryday,
                new CheckButton[] { chkSun, chkMon, chkTue, chkWed, chkThu, chkFri, chkSat },
                new TimePickerControl(CmbHour1, CmbMinute1, CmbAmPm1, LblQueueStart),
                new TimePickerControl(CmbHour2, CmbMinute2, CmbAmPm2, LblQueueStop)
                );

            this.SchedulerPanel.Schedule = defaultSchedule;

            this.SchedulerPanel.ValueChanged += (_, _) =>
            {
                DownloadSchedule? schedule = null;
                if (ChkEnableScheduler.Active)
                {
                    schedule = this.SchedulerPanel.Schedule;
                }
                if (GtkHelper.GetSelectedValue<DownloadQueue>(LbQueues, 1) is DownloadQueue queue)
                {
                    queue.Schedule = schedule;
                }
            };

            this.LbQueues.Selection.Changed += (_, _) =>
            {
                var selected = GtkHelper.GetSelectedValue<DownloadQueue>(LbQueues, 1);
                if (selected != null)
                {
                    UpdateControls(selected);
                }
            };

            this.lvFiles.Selection.Changed += (_, _) => ListSelectionChanged();

            this.ChkEnableScheduler.Toggled += (_, _) =>
            {
                SchedulerPanel.Enabled = this.ChkEnableScheduler.Active;
                if (GtkHelper.GetSelectedValue<DownloadQueue>(LbQueues, 1) is DownloadQueue queue)
                {
                    queue.Schedule = this.ChkEnableScheduler.Active ? this.SchedulerPanel.Schedule : null;
                }
            };

            Hidden += QueueSchedulerDialog_Hidden;
            BtnNew.Clicked += BtnNew_Clicked;
            BtnStart.Clicked += BtnStart_Clicked;
            BtnStop.Clicked += BtnStop_Clicked;
            BtnDel.Clicked += BtnDel_Clicked;
            BtnCancel.Clicked += BtnCancel_Clicked;
            BtnAdd.Clicked += BtnAdd_Clicked;
            BtnRemove.Clicked += BtnRemove_Clicked;
            BtnUp.Clicked += BtnUp_Clicked;
            BtnDown.Clicked += BtnDown_Clicked;
            BtnMoveTo.Clicked += BtnMoveTo_Clicked;
            BtnSave.Clicked += BtnSave_Clicked;

            lvFiles.Selection.Mode = SelectionMode.Multiple;

            SchedulerPanel.Enabled = false;
        }

        private void BtnSave_Clicked(object? sender, EventArgs e)
        {
            SaveQueues();
            Destroy();
            Dispose();
        }

        private void BtnMoveTo_Clicked(object? sender, EventArgs e)
        {
            if (this.filesListStore.IterNChildren() > 0 &&
                this.queueListStore.IterNChildren() > 1 &&
                LbQueues.Selection.CountSelectedRows() > 0)
            {
                var qsd = QueueSelectionDialog.CreateFromGladeFile(this, this.group);
                var queueNames = new List<string>();
                var queueIds = new List<string>();
                var selectedItem = GtkHelper.GetSelectedValue<DownloadQueue>(LbQueues, 1);
                var index = 0;
                foreach (DownloadQueue item in GtkHelper.GetListStoreValues<DownloadQueue>(queueListStore, 1))
                {
                    if (item != selectedItem)
                    {
                        queueNames.Add(item.Name);
                        queueIds.Add(item.ID);
                    }
                    index++;
                }
                var downloadIds = new string[this.lvFiles.Selection.CountSelectedRows()];
                index = 0;
                foreach (InProgressDownloadEntry lvi in GtkHelper.GetSelectedValues<InProgressDownloadEntry>(lvFiles, 3))
                {
                    downloadIds[index++] = lvi.Id;
                }
                qsd.SetData(queueNames, queueIds, downloadIds);
                qsd.QueueSelected += Qsd_QueueSelected;
                qsd.Run();
                qsd.Destroy();
                qsd.Dispose();
            }
        }

        private void Qsd_QueueSelected(object? sender, QueueSelectionEventArgs e)
        {
            var qid = e.SelectedQueueId;
            var queue = GtkHelper.GetListStoreValues<DownloadQueue>(queueListStore, 1).Find(x => x.ID == qid);// GtkHelper.GetValueAt<DownloadQueue>(LbQueues, e.SelectedQueueIndex, 1);
            var downloadIds = e.DownloadIds;
            var selectedQueue = GtkHelper.GetSelectedValue<DownloadQueue>(LbQueues, 1);
            foreach (var id in downloadIds)
            {
                selectedQueue.DownloadIds.Remove(id);
                queue.DownloadIds.Add(id);
            }
            LoadQueueDetails(selectedQueue);
        }

        private void QueueSchedulerDialog_Hidden(object? sender, EventArgs e)
        {
            this.WindowClosing?.Invoke(this, EventArgs.Empty);
        }

        private void BtnDown_Clicked(object? sender, EventArgs e)
        {
            var indices = GtkHelper.GetSelectedIndices(lvFiles);
            if (indices.Length > 0 && indices[indices.Length - 1] < this.filesListStore.IterNChildren() - 1)
            {
                var index = indices[indices.Length - 1] + 1;
                var ent = GtkHelper.GetValueAt<InProgressDownloadEntry>(lvFiles, index, 3);
                if (ent == null) return;
                GtkHelper.RemoveAt(filesListStore, index);
                filesListStore.InsertWithValues(indices[0], ent.Name,
                    Helpers.FormatSize(ent.Size), ent.Status.ToString(), ent);
            }
        }

        private void BtnUp_Clicked(object? sender, EventArgs e)
        {
            var indices = GtkHelper.GetSelectedIndices(lvFiles);
            if (indices.Length > 0 && indices[0] > 0)
            {
                var ent = GtkHelper.GetValueAt<InProgressDownloadEntry>(lvFiles, indices[0] - 1, 3);
                if (ent == null) return;
                var index = indices[indices.Length - 1];
                GtkHelper.RemoveAt(filesListStore, indices[0] - 1);
                filesListStore.InsertWithValues(index, ent.Name,
                    Helpers.FormatSize(ent.Size), ent.Status.ToString(), ent);
            }
            //var indices = GtkHelper.getse lvFiles.GetSelectedIndices();
            //if (indices.Length > 0 && indices[0] > 0)
            //{
            //    var item = this.downloads[indices[0] - 1];
            //    var index = indices[indices.Length - 1];
            //    this.downloads.Remove(item);
            //    this.downloads.Insert(index, item);
            //    //var index1 = indices[0] - 1;
            //    //var index2 = indices[0] + lvFiles.SelectedItems.Count - 1;
            //    //var value = this.downloads[index1];
            //    //this.downloads.RemoveAt(index1);
            //    //this.downloads.Insert(index2, value);
            //}
        }

        private void BtnRemove_Clicked(object? sender, EventArgs e)
        {
            var selectedQueue = GtkHelper.GetSelectedValue<DownloadQueue>(LbQueues, 1);
            if (selectedQueue == null) return;
            var selectedIds = new List<string>();
            foreach (var item in GtkHelper.GetSelectedValues<InProgressDownloadEntry>(lvFiles, 3))
            {
                if (item != null)
                {
                    selectedIds.Add(item.Id);
                }
            }
            foreach (var id in selectedIds)
            {
                selectedQueue.DownloadIds.Remove(id);
            }
            LoadQueueDetails(selectedQueue);
        }

        private void BtnAdd_Clicked(object? sender, EventArgs e)
        {
            var index = GtkHelper.GetSelectedIndex(LbQueues);
            if (index < 0) return;

            var dlg = NewQueueDialog.CreateFromGladeFile(this, this.group, (queue, newQueue) =>
            {
                LoadQueueDetails(queue);
            }, GtkHelper.GetSelectedValue<DownloadQueue>(this.LbQueues, 1));
            dlg.Run();
            dlg.Destroy();
            dlg.Dispose();
        }

        private void BtnCancel_Clicked(object? sender, EventArgs e)
        {
            Result = false;
            this.group.RemoveWindow(this);
            Visible = false;
        }

        private void BtnDel_Clicked(object? sender, EventArgs e)
        {
            var index = GtkHelper.GetSelectedIndex(LbQueues);
            if (GtkHelper.GetSelectedIndex(LbQueues) >= 0)
            {
                GtkHelper.RemoveAt(this.queueListStore, index);
            }
        }

        private void BtnStop_Clicked(object? sender, EventArgs e)
        {
            SaveQueues();
            var queue = GtkHelper.GetSelectedValue<DownloadQueue>(LbQueues, 1);
            if (queue != null)
            {
                QueueStopRequested?.Invoke(this, new DownloadListEventArgs(queue.DownloadIds));
            }
        }

        private void BtnStart_Clicked(object? sender, EventArgs e)
        {
            SaveQueues();
            var queue = GtkHelper.GetSelectedValue<DownloadQueue>(LbQueues, 1);
            QueueStartRequested?.Invoke(this, new DownloadListEventArgs(queue.DownloadIds));
        }

        private void BtnNew_Clicked(object? sender, EventArgs e)
        {
            var dlg = NewQueueDialog.CreateFromGladeFile(this, this.group, (queue, newQueue) =>
            {
                AddToQueueList(queue);
            }, null);
            dlg.Run();
            dlg.Destroy();
            dlg.Dispose();
        }

        public bool Result { get; set; } = false;

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

        private void LoadQueueDetails(DownloadQueue queue)
        {
            lvFiles.Selection.UnselectAll();
            filesListStore.Clear();
            foreach (var id in queue.DownloadIds)
            {
                var ent = AppInstance.Current.GetInProgressDownloadEntry(id);
                if (ent != null)
                {
                    filesListStore.AppendValues(ent.Name, Helpers.FormatSize(ent.Size), ent.Status.ToString(), ent);
                }
            }

            if (queue.Schedule.HasValue)
            {
                this.SchedulerPanel.Schedule = queue.Schedule.Value;
            }
            else
            {
                this.SchedulerPanel.Schedule = this.defaultSchedule;
            }
            ChkEnableScheduler.Active = queue.Schedule.HasValue;
        }

        private void AddToQueueList(DownloadQueue queue)
        {
            queueListStore.AppendValues(queue.Name, queue);
        }

        private void EnableControls(bool enable)
        {
            this.Tab.Sensitive = enable;
            this.BtnNew.Sensitive = this.BtnDel.Sensitive = this.BtnStart.Sensitive = this.BtnStop.Sensitive = enable;
        }

        public void RefreshView()
        {
            if (GtkHelper.GetSelectedValue<DownloadQueue>(LbQueues, 1) is DownloadQueue queue)
            {
                filesListStore.Clear();
                var realQueue = QueueManager.GetQueue(queue.ID);

                if (realQueue != null)
                {
                    foreach (var id in realQueue.DownloadIds)
                    {
                        var ent = AppInstance.Current.GetInProgressDownloadEntry(id);
                        if (ent != null)
                        {
                            filesListStore.AppendValues(ent.Name, Helpers.FormatSize(ent.Size), ent.Status.ToString(), ent);
                        }
                    }
                }
            }
        }

        private void ListSelectionChanged()
        {
            var count = lvFiles.Selection.CountSelectedRows();
            BtnRemove.Sensitive = BtnUp.Sensitive = BtnDown.Sensitive = BtnMoveTo.Sensitive = count > 0;
        }

        public void SetData(IEnumerable<DownloadQueue> queues)
        {
            queueListStore.Clear();
            foreach (var item in queues)
            {
                AddToQueueList(item);
                //queueListStore.AppendValues(item.Name, item);
            }
            if (this.queueListStore.IterNChildren() > 0)
            {
                GtkHelper.SetSelectedIndex(LbQueues, 0);
            }
            ListSelectionChanged();
        }

        public void ShowWindow(object peer)
        {
            this.Run();
            this.Destroy();
            this.Dispose();
        }

        private void SaveQueues()
        {
            QueuesModified?.Invoke(this, new QueueListEventArgs(
                GtkHelper.GetListStoreValues<DownloadQueue>(this.queueListStore, 1)));
        }

        //private TimeSpan GetSchedulerStartTime()
        //{
        //    var hrs = CmbHour1.Active + 1;
        //    var min = CmbMinute1.Active + 1;
        //    return TimeHelper.ConvertH12ToH24(hrs,
        //           min, CmbAmPm1.Active == 0);
        //}

        //private TimeSpan GetSchedulerStopTime()
        //{
        //    var hrs = CmbHour2.Active + 1;
        //    var min = CmbMinute2.Active;
        //    return TimeHelper.ConvertH12ToH24(hrs,
        //           min, CmbAmPm2.Active == 0);
        //}

        //private void SetTime(TimeSpan value, ComboBox cmbHrs, ComboBox cmbMin, ComboBox cmbAmPm)
        //{
        //    suppressEvent = true;
        //    TimeHelper.ConvertH24ToH12(value, out int hh, out int mi, out bool am);
        //    cmbAmPm.Active = am ? 0 : 1;
        //    cmbHrs.Active = hh - 1;
        //    cmbMin.Active = mi;
        //    suppressEvent = false;
        //}

        //private void SetStartTime(TimeSpan value)
        //{
        //    SetTime(value, CmbHour1, CmbMinute1, CmbAmPm1);
        //}

        //private void SetStopTime(TimeSpan value)
        //{
        //    SetTime(value, CmbHour2, CmbMinute2, CmbAmPm2);
        //}

        public static QueueSchedulerDialog CreateFromGladeFile(Window parent, WindowGroup group)
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "queue-manager-dialog.glade"));
            return new QueueSchedulerDialog(builder, parent, group);
        }

        private void LoadTexts()
        {
            BtnNew.Label = TextResource.GetText("DESC_NEW");
            BtnDel.Label = TextResource.GetText("DESC_DEL");
            BtnStart.Label = TextResource.GetText("MENU_START_Q");
            BtnStop.Label = TextResource.GetText("MENU_PAUSE");
            BtnSave.Label = TextResource.GetText("DESC_SAVE_Q");
            BtnCancel.Label = TextResource.GetText("ND_CANCEL");
            BtnAdd.Label = TextResource.GetText("Q_ADD");
            BtnRemove.Label = TextResource.GetText("Q_REMOVE");
            BtnUp.Label = TextResource.GetText("Q_MOVE_UP");
            BtnDown.Label = TextResource.GetText("Q_MOVE_DN");
            BtnMoveTo.Label = TextResource.GetText("Q_MOVE_TO");

            TabHeader1.Text = TextResource.GetText("Q_LIST_FILES");
            TabHeader2.Text = TextResource.GetText("Q_SCHEDULE_TXT");

            ChkEnableScheduler.Label = TextResource.GetText("Q_ENABLE");
            chkEveryday.Label = TextResource.GetText("MSG_Q_DAILY");
            chkSun.Label = TextResource.GetText("MSG_Q_D1");
            chkMon.Label = TextResource.GetText("MSG_Q_D2");
            chkTue.Label = TextResource.GetText("MSG_Q_D3");
            chkWed.Label = TextResource.GetText("MSG_Q_D4");
            chkThu.Label = TextResource.GetText("MSG_Q_D5");
            chkFri.Label = TextResource.GetText("MSG_Q_D6");
            chkSat.Label = TextResource.GetText("MSG_Q_D7");
            LblQueueStart.Text = TextResource.GetText("MSG_Q_START");
            LblQueueStop.Text = TextResource.GetText("MSG_Q_STOP");

        }
    }
}
