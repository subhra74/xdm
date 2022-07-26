using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Gtk;
using GLib;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Core;
using Translations;
using UI = Gtk.Builder.ObjectAttribute;
using XDM.GtkUI.Utils;
using XDM.Core;
using XDM.Core.Util;

namespace XDM.GtkUI.Dialogs.NewQueue
{
    internal class NewQueueDialog : Dialog
    {
        [UI] private Label LblQueueName;
        [UI] private Label LblQueueSelection;
        [UI] private Entry TxtQueueName;
        [UI] private TreeView lvDownloads;
        [UI] private CheckButton ChkSelectAll;
        [UI] private Button BtnOK;
        [UI] private Button BtnCancel;

        private WindowGroup group;
        private DownloadQueue? modifyingQueue;
        private Action<DownloadQueue, bool> okAction;
        private ListStore listStore;

        public bool Result { get; set; } = false;

        private NewQueueDialog(Builder builder,
            Window parent,
            WindowGroup group,
            Action<DownloadQueue, bool> okAction,
            DownloadQueue? modifyingQueue) : base(builder.GetRawOwnedObject("dialog"))
        {
            builder.Autoconnect(this);

            Modal = true;
            SetPosition(WindowPosition.CenterAlways);
            TransientFor = parent;
            this.group = group;
            this.group.AddWindow(this);
            GtkHelper.AttachSafeDispose(this);
            LoadTexts();
            Title = TextResource.GetText("LBL_QUEUE_OPT1");
            SetDefaultSize(640, 480);
            BtnCancel.Clicked += BtnCancel_Clicked;
            BtnOK.Clicked += BtnOK_Clicked;

            this.listStore = new ListStore(
                typeof(bool), typeof(string),
                typeof(string), typeof(string),
                typeof(string), typeof(InProgressEntryWrapper));

            this.lvDownloads.Model = this.listStore;

            var fileNameColumn = new TreeViewColumn
            {
                Resizable = true,
                Reorderable = false,
                Title = TextResource.GetText("SORT_NAME"),
                Sizing = TreeViewColumnSizing.Fixed,
                FixedWidth = 200
            };

            var checkboxRenderer = new CellRendererToggle { };
            checkboxRenderer.Toggled += CheckboxRenderer_Toggled;
            fileNameColumn.PackStart(checkboxRenderer, false);
            fileNameColumn.SetAttributes(checkboxRenderer, "active", 0);

            var fileNameRendererText = new CellRendererText();
            fileNameColumn.PackStart(fileNameRendererText, false);
            fileNameColumn.SetAttributes(fileNameRendererText, "text", 1);
            this.lvDownloads.AppendColumn(fileNameColumn);

            var dateColumn = new TreeViewColumn
            {
                Resizable = true,
                Reorderable = false,
                Title = TextResource.GetText("SORT_DATE"),
                Sizing = TreeViewColumnSizing.Fixed,
                FixedWidth = 150
            };
            var dateRendererText = new CellRendererText();
            dateColumn.PackStart(dateRendererText, false);
            dateColumn.SetAttributes(dateRendererText, "text", 2);
            this.lvDownloads.AppendColumn(dateColumn);

            var sizeColumn = new TreeViewColumn
            {
                Resizable = true,
                Reorderable = false,
                Title = TextResource.GetText("SORT_SIZE"),
                Sizing = TreeViewColumnSizing.Fixed,
                FixedWidth = 150
            };
            var sizeRendererText = new CellRendererText();
            sizeColumn.PackStart(sizeRendererText, false);
            sizeColumn.SetAttributes(sizeRendererText, "text", 3);
            this.lvDownloads.AppendColumn(sizeColumn);

            var statusColumn = new TreeViewColumn
            {
                Resizable = true,
                Reorderable = false,
                Title = TextResource.GetText("SORT_STATUS"),
                Sizing = TreeViewColumnSizing.Fixed,
                FixedWidth = 150
            };
            var statusRendererText = new CellRendererText();
            statusColumn.PackStart(statusRendererText, false);
            statusColumn.SetAttributes(statusRendererText, "text", 4);
            this.lvDownloads.AppendColumn(statusColumn);

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

            foreach (var ent in ApplicationContext.Application.GetAllInProgressDownloads().Select(x => new EntryWrapper { Entry = x }))
            {
                if (!set.Contains(ent.Entry.Id))
                {
                    listStore.AppendValues(false,
                        ent.Entry.Name,
                        ent.Entry.DateAdded.ToShortDateString() + " " + ent.Entry.DateAdded.ToShortTimeString(),
                        FormattingHelper.FormatSize(ent.Entry.Size),
                        ent.Entry.Status.ToString(),
                        ent);
                }
            }

            ChkSelectAll.Toggled += ChkSelectAll_Toggled;
        }

        private void ChkSelectAll_Toggled(object? sender, EventArgs e)
        {
            GtkHelper.ListStoreForEach(this.listStore, iter =>
            {
                this.listStore.SetValue(iter, 0, ChkSelectAll.Active);
                var ent = (EntryWrapper)this.listStore.GetValue(iter, 5);
                ent.Selected = ChkSelectAll.Active;
            });
        }

        private void BtnOK_Clicked(object? sender, EventArgs e)
        {
            if (OnApproved())
            {
                Result = true;
                this.group.RemoveWindow(this);
                Visible = false;
            }
        }

        private bool OnApproved()
        {
            if (string.IsNullOrEmpty(TxtQueueName.Text))
            {
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_QUEUE_NAME_MISSING"));
                return false;
            }
            var list2 = new List<string>(this.listStore.IterNChildren());
            var list = GtkHelper.GetListStoreValues<EntryWrapper>(this.listStore, 5);
            foreach (var entry in list)
            {
                if (entry.Selected)
                {
                    list2.Add(entry.Entry.Id);
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

        private void CheckboxRenderer_Toggled(object o, ToggledArgs args)
        {
            TreeIter iter;
            if (this.listStore.GetIter(out iter, new TreePath(args.Path)))
            {
                var value = !(bool)this.listStore.GetValue(iter, 0);
                this.listStore.SetValue(iter, 0, value);
                var ent = (EntryWrapper)this.listStore.GetValue(iter, 5);
                ent.Selected = value;
            }
        }

        private void BtnCancel_Clicked(object? sender, EventArgs e)
        {
            Result = false;
            this.group.RemoveWindow(this);
            Visible = false;
        }

        private void LoadTexts()
        {
            LblQueueName.Text = TextResource.GetText("MSG_QUEUE_NAME");
            LblQueueSelection.Text = TextResource.GetText("MSG_QUEUE_SELECT_ITEMS");
            ChkSelectAll.Label = TextResource.GetText("VID_CHK");
            BtnOK.Label = TextResource.GetText("MSG_OK");
            BtnCancel.Label = TextResource.GetText("ND_CANCEL");
        }

        public static NewQueueDialog CreateFromGladeFile(Window parent, WindowGroup group,
            Action<DownloadQueue, bool> okAction,
            DownloadQueue? modifyingQueue)
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "new-queue-dialog.glade"));
            return new NewQueueDialog(builder, parent, group, okAction, modifyingQueue);
        }
    }

    internal class EntryWrapper
    {
        public InProgressDownloadEntry Entry { get; set; }
        public bool Selected { get; set; }
    }
}
