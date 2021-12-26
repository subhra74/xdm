using System;

using System.Windows.Forms;
using Translations;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Downloader;

namespace XDM.WinForm.UI
{
    public partial class UpdaterWindow : Form, IUpdaterUI
    {
        private IAppUI AppUI;
        public UpdaterWindow(IAppUI AppUI)
        {
            InitializeComponent();
            this.AppUI = AppUI;
            Text = TextResource.GetText("OPT_UPDATE_FFMPEG");
            label1.Text = TextResource.GetText("STAT_DOWNLOADING");
            button1.Text = TextResource.GetText("ND_CANCEL");
        }

        public event EventHandler? Cancelled;
        public event EventHandler? Finished;

        public void DownloadCancelled(object? sender, EventArgs e)
        {
            AppUI.RunOnUiThread(() => Dispose());
        }

        public void DownloadFailed(object? sender, DownloadFailedEventArgs e)
        {
            MessageBox.Show(TextResource.GetText("MSG_FAILED"));
            AppUI.RunOnUiThread(() => Dispose());
        }

        public void DownloadFinished(object? sender, EventArgs e)
        {
            MessageBox.Show(TextResource.GetText("MSG_UPDATED"));
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
            MessageBox.Show(TextResource.GetText("MSG_NO_UPDATE"));
            AppUI.RunOnUiThread(() => Dispose());
            this.Finished?.Invoke(this, EventArgs.Empty);
        }
    }
}
