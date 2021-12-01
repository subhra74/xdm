using System;
using System.Collections.Generic;
using System.Windows.Forms;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Util;
using XDMApp;

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
                    MessageBox.Show("Please specify queue name");
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
        }

        private void button3_Click(object sender, EventArgs e)
        {
            foreach (ListViewItem lvi in listView1.Items)
            {
                lvi.Checked = true;
            }
        }

        private void button4_Click(object sender, EventArgs e)
        {
            foreach (ListViewItem lvi in listView1.Items)
            {
                lvi.Checked = false;
            }
        }

        private void button2_Click_1(object sender, EventArgs e)
        {
            Close();
        }
    }
}
