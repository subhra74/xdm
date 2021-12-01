using System;
using System.Windows.Forms;
using XDM.Core.Lib.Common;

namespace XDM.WinForm.UI
{
    public partial class SchedulerWindow : Form
    {
        private SchedulerPanel schedulerPanel;
        public DownloadSchedule Schedule
        {
            get => schedulerPanel.Schedule;
            set => schedulerPanel.Schedule = value;
        }

        public SchedulerWindow()
        {
            InitializeComponent();
            this.schedulerPanel = new SchedulerPanel { Dock = DockStyle.Fill };
            this.Controls.Add(this.schedulerPanel);
        }

        private void button2_Click(object sender, EventArgs e)
        {
            this.DialogResult = DialogResult.OK;
            this.Dispose();
        }

        private void button1_Click(object sender, EventArgs e)
        {
            this.DialogResult = DialogResult.Cancel;
            this.Dispose();
        }
    }
}
