using System;
using System.Collections.Generic;
using System.Windows.Forms;
using Translations;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Util;
using XDMApp;

#if !(NET472_OR_GREATER||NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI
{
    public partial class NewQueueDialog : Form
    {
        public NewQueueDialog(IAppUI ui, Action<DownloadQueue, bool> okAction, DownloadQueue? modifyingQueue)
        {
            InitializeComponent();
            if (modifyingQueue == null)
            {
                this.textBox1.Text = "New queue #" + QueueManager.QueueAutoNumber;
                QueueManager.QueueAutoNumber++;
            }
            else
            {
                this.textBox1.Text = modifyingQueue.Name;
            }
            listView1.Items.Clear();
            listView1.CheckBoxes = true;
            var list = new List<InProgressDownloadEntry>(ui.GetAllInProgressDownloads());
            var set = new HashSet<string>();

            foreach (var queue in QueueManager.Queues)
            {
                foreach (var id in queue.DownloadIds)
                {
                    set.Add(id);
                }
            }

            foreach (var ent in list)
            {
                if (!set.Contains(ent.Id))
                {
                    var arr = new string[]
                    {
                    ent.Name,
                    ent.DateAdded.ToShortDateString(),
                    Helpers.FormatSize(ent.Size),
                    ent.Status==DownloadStatus.Downloading?$"{ent.Progress}%":ent.Status.ToString()
                    };
                    listView1.Items.Add(new ListViewItem(arr) { Checked = false, Tag = ent });
                }
            }

            button1.Click += (a, b) =>
            {
                if (string.IsNullOrEmpty(textBox1.Text))
                {
                    MessageBox.Show(TextResource.GetText("MSG_QUEUE_NAME_MISSING"));
                    return;
                }
                var list = new List<string>(listView1.CheckedItems.Count);
                foreach (ListViewItem lvi in listView1.CheckedItems)
                {
                    list.Add(((InProgressDownloadEntry)lvi.Tag).Id);
                }
                if (modifyingQueue == null)
                {
                    okAction.Invoke(new DownloadQueue(Guid.NewGuid().ToString(), textBox1.Text) { DownloadIds = list }, true);
                }
                else
                {
                    modifyingQueue.DownloadIds.AddRange(list);
                    okAction.Invoke(modifyingQueue, false);
                }
                Close();
            };

            button1.Margin = button2.Margin = checkBox1.Margin =
                new Padding(LogicalToDeviceUnits(3),
                LogicalToDeviceUnits(12),
                LogicalToDeviceUnits(12),
                LogicalToDeviceUnits(3));

            foreach (ColumnHeader col in listView1.Columns)
            {
                col.Width = col.Index == 0 ? LogicalToDeviceUnits(200) : LogicalToDeviceUnits(100);
            }

            LoadTexts();
        }

        private void button2_Click_1(object sender, EventArgs e)
        {
            Close();
        }

        private void LoadTexts()
        {
            Text = TextResource.GetText("LBL_QUEUE_OPT1");
            label1.Text = TextResource.GetText("MSG_QUEUE_NAME");
            label2.Text = TextResource.GetText("MSG_QUEUE_SELECT_ITEMS");
            listView1.Columns[0].Text = TextResource.GetText("SORT_NAME");
            listView1.Columns[1].Text = TextResource.GetText("SORT_DATE");
            listView1.Columns[2].Text = TextResource.GetText("SORT_SIZE");
            listView1.Columns[3].Text = TextResource.GetText("SORT_STATUS");
            checkBox1.Text = TextResource.GetText("VID_CHK");
            button1.Text = TextResource.GetText("MSG_OK");
            button2.Text = TextResource.GetText("ND_CANCEL");
        }

        private void checkBox1_CheckedChanged(object sender, EventArgs e)
        {
            foreach (ListViewItem lvi in listView1.Items)
            {
                lvi.Checked = checkBox1.Checked;
            }
        }
    }
}
