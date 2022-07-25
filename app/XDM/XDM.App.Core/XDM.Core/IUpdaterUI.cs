using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

using XDM.Core;
using XDM.Core.Downloader;

namespace XDM.Core
{
    public interface IUpdaterUI
    {
        public void DownloadStarted(object? sender, EventArgs e);

        public void DownloadProgressChanged(object? sender, ProgressResultEventArgs e);

        public void DownloadFailed(object? sender, DownloadFailedEventArgs e);

        public void ShowNoUpdateMessage();

        public void DownloadFinished(object? sender, EventArgs e);

        public void DownloadCancelled(object? sender, EventArgs e);

        public event EventHandler? Cancelled;

        public event EventHandler? Finished;

        public event EventHandler? Load;

        public string Label { get; set; }

        public void Show();

        public bool Inderminate { get; set; }
    }
}
