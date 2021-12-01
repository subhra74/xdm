using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Text;

using System.Windows.Forms;
using XDM.Common.UI;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Common;

namespace XDM.WinForm.UI
{
    public partial class UpdaterWindow : Form, IUpdaterUI
    {
        private IAppUI AppUI;
        public UpdaterWindow(IAppUI AppUI)
        {
            InitializeComponent();
            this.AppUI = AppUI;
        }

        public event EventHandler? Cancelled;
        public event EventHandler? Finished;

        public void DownloadCancelled(object? sender, EventArgs e)
        {
            AppUI.RunOnUiThread(() => Dispose());
        }

        public void DownloadFailed(object? sender, DownloadFailedEventArgs e)
        {
            MessageBox.Show("Update failed");
            AppUI.RunOnUiThread(() => Dispose());
        }

        public void DownloadFinished(object? sender, EventArgs e)
        {
            MessageBox.Show("Update successfull");
            this.Finished?.Invoke(sender, e);
            AppUI.RunOnUiThread(() => Dispose());
        }

        public void DownloadProgressChanged(object? sender, ProgressResultEventArgs e)
        {
            AppUI.RunOnUiThread(() => progressBar1.Value = e.Progress);
        }

        public void DownloadStarted(object? sender, EventArgs e)
        {

        }

        public string Label { get => label1.Text; set => AppUI.RunOnUiThread(() => label1.Text = value); }

        public bool Inderminate
        {
            get => progressBar1.Style == ProgressBarStyle.Marquee; set
            {
                AppUI.RunOnUiThread(() => progressBar1.Style = value ? ProgressBarStyle.Marquee : ProgressBarStyle.Blocks);
            }
        }

        private void button1_Click(object sender, EventArgs e)
        {
            Cancelled?.Invoke(sender, e);
        }

        public void ShowNoUpdateMessage()
        {
            MessageBox.Show("No updates available/already upto date");
            AppUI.RunOnUiThread(() => Dispose());
            this.Finished?.Invoke(this, EventArgs.Empty);
        }
    }
}
