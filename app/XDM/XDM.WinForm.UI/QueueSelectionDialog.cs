using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Text;

using System.Windows.Forms;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;

namespace XDM.WinForm.UI
{
    public partial class QueueSelectionDialog : Form, IQueueSelectionDialog
    {
        public event EventHandler<QueueSelectionEventArgs>? QueueSelected;
        public event EventHandler? ManageQueuesClicked;
        private string[] downloadIds = new string[0];

        public bool ShowManageQueueOption { get => linkLabel1.Visible; set => linkLabel1.Visible = value; }

        public QueueSelectionDialog()
        {
            InitializeComponent();
        }

        public void SetData(IEnumerable<string> items, string[] downloadIds)
        {
            this.downloadIds = downloadIds;
            foreach (var item in items)
            {
                listBox1.Items.Add(item);
            }

            listBox1.SelectedIndex = 0;
        }

        public void ShowWindow(IAppWinPeer peer)
        {
            Show((IWin32Window)peer);
        }

        private void button1_Click(object sender, EventArgs e)
        {
            QueueSelected = null;
            Close();
        }

        private void button2_Click(object sender, EventArgs e)
        {
            QueueSelected?.Invoke(this, new QueueSelectionEventArgs(listBox1.SelectedIndex, downloadIds));
            QueueSelected = null;
            Close();
        }

        private void linkLabel1_LinkClicked(object sender, LinkLabelLinkClickedEventArgs e)
        {
            ManageQueuesClicked?.Invoke(this, EventArgs.Empty);
            Close();
        }
    }
}
