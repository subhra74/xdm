using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

using Gtk;
using System.IO;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;
using XDM.GtkUI.Utils;
using Translations;
using UI = Gtk.Builder.ObjectAttribute;
using XDM.Core.Lib.Util;
using XDMApp;

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
        private IAppUI appUI;
        private byte[] bits;
        private readonly CheckButton[] checkboxes;
        private DownloadSchedule defaultSchedule;
        private SchedulerPanelControl SchedulerPanel;

        public event EventHandler<QueueListEventArgs>? QueuesModified;
        public event EventHandler<DownloadListEventArgs>? QueueStartRequested;
        public event EventHandler<DownloadListEventArgs>? QueueStopRequested;
        public event EventHandler? WindowClosing;

        private QueueSchedulerDialog(Builder builder, Window parent, WindowGroup group, IAppUI appUI) : base(builder.GetRawOwnedObject("dialog"))
        {
            builder.Autoconnect(this);
            Modal = true;
            SetPosition(WindowPosition.Center);
            TransientFor = parent;
            this.group = group;
            this.group.AddWindow(this);

            GtkHelper.AttachSafeDispose(this);
            Title = TextResource.GetText("DESC_Q_TITLE");
            SetDefaultSize(700, 500);
            LoadTexts();

            this.appUI = appUI;
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
                new TimePickerControl(CmbHour1, CmbMinute1, CmbAmPm1),
                new TimePickerControl(CmbHour2, CmbMinute2, CmbAmPm2)
                );

            this.SchedulerPanel.Schedule = defaultSchedule;

            this.SchedulerPanel.ValueChanged += (_, _) =>
            {
                if (ChkEnableScheduler.Active)
                {
                    if (GtkHelper.SetSelectedValue<DownloadQueue>(LbQueues, 1) is DownloadQueue queue)
                    {
                        queue.Schedule = this.SchedulerPanel.Schedule;
                    }
                }
            };

            this.LbQueues.Selection.Changed += (_, _) =>
            {
                var selected = GtkHelper.SetSelectedValue<DownloadQueue>(LbQueues, 1);
                if (selected != null)
                {
                    UpdateControls(selected);
                }
            };

            this.lvFiles.Selection.Changed += (_, _) => ListSelectionChanged();

            this.ChkEnableScheduler.Toggled += (_, _) =>
            {
                SchedulerPanel.Enabled = this.ChkEnableScheduler.Active;
                if (GtkHelper.SetSelectedValue<DownloadQueue>(LbQueues, 1) is DownloadQueue queue)
                {
                    queue.Schedule = this.ChkEnableScheduler.Active ? this.SchedulerPanel.Schedule : null;
                }
            };

            DeleteEvent += (_, _) =>
                  this.WindowClosing?.Invoke(this, EventArgs.Empty);
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
                var ent = appUI.GetInProgressDownloadEntry(id);
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

        private void EnableControls(bool enable)
        {
            this.Tab.Sensitive = enable;
            this.BtnNew.Sensitive = this.BtnDel.Sensitive = this.BtnStart.Sensitive = this.BtnStop.Sensitive = enable;
        }

        public void RefreshView()
        {
            if (GtkHelper.SetSelectedValue<DownloadQueue>(LbQueues, 1) is DownloadQueue queue)
            {
                filesListStore.Clear();
                var realQueue = QueueManager.GetQueue(queue.ID);

                if (realQueue != null)
                {
                    foreach (var id in realQueue.DownloadIds)
                    {
                        var ent = appUI.GetInProgressDownloadEntry(id);
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
            BtnRemove.Sensitive = BtnUp.Sensitive = BtnDown.Sensitive = BtnMoveTo.Sensitive = filesListStore.IterNChildren() > 0;
        }

        public void SetData(IEnumerable<DownloadQueue> queues)
        {
            queueListStore.Clear();
            foreach (var item in queues)
            {
                queueListStore.AppendValues(item.Name, item);
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

        public static QueueSchedulerDialog CreateFromGladeFile(Window parent, WindowGroup group, IAppUI appUI)
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "queue-manager-dialog.glade"));
            return new QueueSchedulerDialog(builder, parent, group, appUI);
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
