using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;
using XDM.Core.Lib.Util;
using XDMApp;

#if !(NET472_OR_GREATER||NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI
{
    public partial class QueuesWindow : Form, IQueuesWindow
    {
        private IAppUI appUI;
        private SchedulerPanel schedulerPanel;
        private DownloadSchedule defaultSchedule;

        public event EventHandler<QueueListEventArgs>? QueuesModified;
        public event EventHandler<DownloadListEventArgs>? QueueStartRequested;
        public event EventHandler<DownloadListEventArgs>? QueueStopRequested;
        public event EventHandler? WindowClosing;

        private NewQueueDialog? newQueueDialog;
        private QueueSelectionDialog? queueSelectionDialog;

        public QueuesWindow(IAppUI appUI)
        {
            InitializeComponent();
            this.appUI = appUI;
            this.defaultSchedule = new DownloadSchedule
            {
                StartTime = DateTime.Now.TimeOfDay,
                EndTime = DateTime.Now.Date.AddHours(23).AddMinutes(59).TimeOfDay
            };
            schedulerPanel = new SchedulerPanel()
            {
                Dock = DockStyle.Fill,
                Enabled = false,
                Schedule = defaultSchedule
            };
            schedulerPanel.ValueChanged += SchedulerPanel_ValueChanged;
            panel1.Controls.Add(schedulerPanel);
            treeView1.AfterSelect += TreeView1_AfterSelect;
            listView1.SelectedIndexChanged += ListView1_SelectedIndexChanged;
            listView1.ItemSelectionChanged += ListView1_ItemSelectionChanged;

            checkBox1.CheckedChanged += (_, _) =>
            {
                schedulerPanel.Enabled = checkBox1.Checked;
            };

            this.FormClosing += (s, e) =>
            {
                this.newQueueDialog?.Close();
                this.newQueueDialog = null;
                this.queueSelectionDialog?.Close();
                this.queueSelectionDialog = null;
                this.WindowClosing?.Invoke(this, EventArgs.Empty);
            };

            treeView1.Margin = new Padding(LogicalToDeviceUnits(12),
                LogicalToDeviceUnits(12),
                LogicalToDeviceUnits(6),
                LogicalToDeviceUnits(6));
            tabPage1.Padding = new Padding(LogicalToDeviceUnits(6));
            tabPage1.Margin = new Padding(LogicalToDeviceUnits(3));
            tabPage2.Padding = new Padding(LogicalToDeviceUnits(6));
            tabPage2.Margin = new Padding(LogicalToDeviceUnits(3));
            tabControl1.Padding = new Point(LogicalToDeviceUnits(10), LogicalToDeviceUnits(5));
            columnHeader1.Width = LogicalToDeviceUnits(150);
            columnHeader2.Width = LogicalToDeviceUnits(100);
            columnHeader3.Width = LogicalToDeviceUnits(100);

            var buttonMargin = new Padding(LogicalToDeviceUnits(6), LogicalToDeviceUnits(2),
                LogicalToDeviceUnits(6), LogicalToDeviceUnits(2));
            button1.Padding = button2.Padding = button3.Padding = button3.Padding =
                button4.Padding = button5.Padding = button6.Padding = button7.Padding =
                button9.Padding = button10.Padding = btnUp.Padding = btnDown.Padding = buttonMargin;
        }

        private void ListSelectionChanged()
        {
            button9.Enabled = button10.Enabled = btnUp.Enabled = btnDown.Enabled = listView1.SelectedItems.Count > 0;
        }

        private void ListView1_ItemSelectionChanged(object sender, ListViewItemSelectionChangedEventArgs e)
        {
            ListSelectionChanged();
        }

        private void ListView1_SelectedIndexChanged(object sender, EventArgs e)
        {
            ListSelectionChanged();
        }

        private void SchedulerPanel_ValueChanged(object sender, EventArgs e)
        {
            if (this.treeView1.SelectedNode?.Tag is DownloadQueue queue && checkBox1.Checked)
            {
                queue.Schedule = this.schedulerPanel.Schedule;
            }
        }

        public void RefreshView()
        {
            if (treeView1.SelectedNode.Tag is DownloadQueue queue)
            {
                listView1.SuspendLayout();

                var set1 = new HashSet<string>();
                var set2 = new HashSet<string>();
                var toRemove = new HashSet<ListViewItem>();

                var realQueue = QueueManager.GetQueue(queue.ID);

                if (realQueue != null)
                {
                    foreach (var id in realQueue.DownloadIds)
                    {
                        set1.Add(id);
                    }
                }

                foreach (ListViewItem lvi in this.listView1.Items)
                {
                    var id = ((InProgressDownloadEntry)lvi.Tag).Id;
                    set2.Add(id);
                    if (set1.Contains(id))
                    {
                        continue;
                    }
                    toRemove.Add(lvi);
                }

                foreach (var lvi in toRemove)
                {
                    listView1.Items.Remove(lvi);
                }

                foreach (var id in set1)
                {
                    if (!set2.Contains(id))
                    {
                        var ent = appUI.GetInProgressDownloadEntry(id);
                        if (ent != null)
                        {
                            var listViewItem = new ListViewItem(new string[] { ent.Name,
                                    ent.DateAdded.ToShortDateString(), Helpers.FormatSize(ent.Size) })
                            { Tag = ent };
                            listView1.Items.Add(listViewItem);
                        }
                    }
                }

                listView1.ResumeLayout();
            }
        }

        public void SetData(IEnumerable<DownloadQueue> queues)
        {
            var rootNode = new TreeNode("Queues");
            treeView1.Nodes.Add(rootNode);
            foreach (var q in queues)
            {
                rootNode.Nodes.Add(new TreeNode(q.Name) { Tag = q });
            }
            rootNode.ExpandAll();
            if (rootNode.Nodes.Count > 0)
            {
                treeView1.SelectedNode = rootNode.Nodes[0];
            }
        }

        private void LoadQueueDetails(DownloadQueue queue)
        {
            listView1.Items.Clear();
            listView1.SuspendLayout();
            ListSelectionChanged();
            foreach (var id in queue.DownloadIds)
            {
                var ent = appUI.GetInProgressDownloadEntry(id);
                if (ent != null)
                {
                    var listViewItem = new ListViewItem(new string[] { ent.Name,
                        ent.DateAdded.ToShortDateString(), Helpers.FormatSize(ent.Size) })
                    { Tag = ent };
                    listView1.Items.Add(listViewItem);
                }
            }
            checkBox1.Checked = queue.Schedule.HasValue;
            if (queue.Schedule.HasValue)
            {
                this.schedulerPanel.Schedule = queue.Schedule.Value;
            }
            else
            {
                this.schedulerPanel.Schedule = this.defaultSchedule;
            }
            listView1.ResumeLayout();
            this.schedulerPanel.Schedule = queue.Schedule ?? default;
        }

        private void TreeView1_AfterSelect(object sender, TreeViewEventArgs e)
        {
            UpdateControls((DownloadQueue)e.Node.Tag);
            //var node = e.Node;

            //if (node.Tag is DownloadQueue queue)
            //{
            //    LoadQueueDetails(queue);
            //    EnableControls(true);
            //}
            //else
            //{
            //    this.schedulerPanel.Schedule = defaultSchedule;
            //    this.listView1.Items.Clear();
            //    EnableControls(false);
            //}
        }

        private void EnableControls(bool enable)
        {
            this.tabControl1.Enabled = enable;
            this.button1.Enabled = this.button2.Enabled = this.button3.Enabled = this.button4.Enabled = enable;
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
                this.schedulerPanel.Schedule = defaultSchedule;
                this.listView1.Items.Clear();
                EnableControls(false);
            }
        }

        private void button5_Click(object sender, EventArgs e)
        {
            SaveQueues();
            Close();
        }

        private void SaveQueues()
        {
            QueuesModified?.Invoke(this, new QueueListEventArgs(GetQueues()));
        }

        private List<DownloadQueue> GetQueues()
        {
            var list = new List<DownloadQueue>(this.treeView1.Nodes[0].Nodes.Count);
            foreach (TreeNode node in this.treeView1.Nodes[0].Nodes)
            {
                list.Add((DownloadQueue)node.Tag);
            }
            return list;
        }

        private void button3_Click(object sender, EventArgs e)
        {
            SaveQueues();
            var node = treeView1.SelectedNode;
            QueueStartRequested?.Invoke(this, new DownloadListEventArgs(((DownloadQueue)node.Tag).DownloadIds));
        }

        private void button4_Click(object sender, EventArgs e)
        {
            SaveQueues();
            var node = treeView1.SelectedNode;
            QueueStopRequested?.Invoke(this, new DownloadListEventArgs(((DownloadQueue)node.Tag).DownloadIds));
        }

        public void ShowWindow(object window)
        {
            if (!this.Visible)
            {
                this.Show((IWin32Window)window);
            }
        }

        private void button1_Click(object sender, EventArgs e)
        {
            if (this.newQueueDialog != null)
            {
                this.newQueueDialog.BringToFront();
                return;
            }
            this.newQueueDialog = new NewQueueDialog(appUI, (queue, newQueue) =>
            {
                var node = new TreeNode(queue.Name)
                {
                    Tag = queue
                };
                treeView1.Nodes[0].Nodes.Add(node);
            }, null);
            this.newQueueDialog.FormClosing += NewQueueDialog_FormClosed;
            newQueueDialog.Show(this);
            tableLayoutPanel1.Enabled = false;
            //var queue = new DownloadQueue(Guid.NewGuid().ToString(), "New queue #" + QueueManager.QueueAutoNumber);
            //QueueManager.QueueAutoNumber++;
            //var node = new TreeNode(queue.Name)
            //{
            //    Tag = queue
            //};
            //treeView1.Nodes[0].Nodes.Add(node);
            //QueueStopRequested?.Invoke(this, new DownloadListEventArgs(((DownloadQueue)node.Tag).DownloadIds));
        }

        private void NewQueueDialog_FormClosed(object sender, FormClosingEventArgs e)
        {
            this.newQueueDialog!.FormClosing -= NewQueueDialog_FormClosed;
            this.newQueueDialog = null;
            tableLayoutPanel1.Enabled = true;
        }

        private void button2_Click(object sender, EventArgs e)
        {
            var queue = (DownloadQueue)treeView1.SelectedNode.Tag;
            if (queue != null)
            {
                var parent = treeView1.SelectedNode.Parent;
                parent.Nodes.Remove(treeView1.SelectedNode);
            }
        }

        private void button6_Click(object sender, EventArgs e)
        {
            Close();
        }

        private void button7_Click(object sender, EventArgs e)
        {
            if (this.newQueueDialog != null)
            {
                this.newQueueDialog.BringToFront();
                return;
            }
            var selectedNode = treeView1.SelectedNode;
            if (selectedNode.Tag is not DownloadQueue queue) return;
            this.newQueueDialog = new NewQueueDialog(appUI, (queue, newQueue) =>
            {
                treeView1.SelectedNode = selectedNode;
                LoadQueueDetails(queue);
            }, queue);
            this.newQueueDialog.FormClosing += NewQueueDialog_FormClosed;
            newQueueDialog.Show(this);
            tableLayoutPanel1.Enabled = false;
        }

        private void button9_Click(object sender, EventArgs e)
        {
            var selectedQueue = (DownloadQueue)treeView1.SelectedNode.Tag;
            if (selectedQueue == null) return;
            foreach (ListViewItem lvi in listView1.SelectedItems)
            {
                var id = ((InProgressDownloadEntry)lvi.Tag).Id;
                selectedQueue.DownloadIds.Remove(id);
            }
            LoadQueueDetails(selectedQueue);
        }

        private void btnUp_Click(object sender, EventArgs e)
        {
            if (listView1.SelectedIndices.Count > 0 && listView1.SelectedIndices[0] > 0)
            {
                var lvi = listView1.Items[listView1.SelectedIndices[0] - 1];
                listView1.Items.Remove(lvi);
                listView1.Items.Insert(listView1.SelectedIndices[listView1.SelectedIndices.Count - 1] + 1, lvi);
            }
        }

        private void btnDown_Click(object sender, EventArgs e)
        {
            if (listView1.SelectedIndices.Count > 0 && listView1.SelectedIndices[listView1.SelectedIndices.Count - 1] < listView1.Items.Count - 1)
            {
                var lvi = listView1.Items[listView1.SelectedIndices[listView1.SelectedIndices.Count - 1] + 1];
                listView1.Items.Remove(lvi);
                listView1.Items.Insert(listView1.SelectedIndices[0], lvi);
            }
        }

        private void button10_Click(object sender, EventArgs e)
        {
            if (listView1.SelectedItems.Count > 0 && treeView1.Nodes[0].Nodes.Count > 1)
            {
                tableLayoutPanel1.Enabled = false;
                this.queueSelectionDialog = new QueueSelectionDialog() { ShowManageQueueOption = false };
                var queues = new List<string>(this.treeView1.Nodes[0].Nodes.Count);
                foreach (TreeNode node in this.treeView1.Nodes[0].Nodes)
                {
                    if (!node.IsSelected)
                    {
                        queues.Add(((DownloadQueue)node.Tag).Name);
                    }
                }
                var downloadIds = new string[this.listView1.SelectedIndices.Count];
                var index = 0;
                foreach (ListViewItem lvi in this.listView1.SelectedItems)
                {
                    downloadIds[index++] = ((InProgressDownloadEntry)lvi.Tag).Id;
                }
                this.queueSelectionDialog.SetData(queues, downloadIds);
                this.queueSelectionDialog.FormClosing += QueueSelectionDialog_FormClosing;
                this.queueSelectionDialog.QueueSelected += QueueSelectionDialog_QueueSelected;
                this.queueSelectionDialog.Show(this);
            }
        }

        private void QueueSelectionDialog_QueueSelected(object sender, QueueSelectionEventArgs e)
        {
            var node = treeView1.Nodes[0].Nodes[e.SelectedQueueIndex];
            var queue = (DownloadQueue)node.Tag;
            var downloadIds = e.DownloadIds;
            var selectedQueue = (DownloadQueue)treeView1.SelectedNode.Tag;
            foreach (var id in downloadIds)
            {
                selectedQueue.DownloadIds.Remove(id);
                queue.DownloadIds.Add(id);
            }
            treeView1.SelectedNode = node;
        }

        private void QueueSelectionDialog_FormClosing(object sender, FormClosingEventArgs e)
        {
            this.queueSelectionDialog!.FormClosing -= QueueSelectionDialog_FormClosing;
            this.queueSelectionDialog = null;
            this.tableLayoutPanel1.Enabled = true;
        }
    }
}
