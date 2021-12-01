using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using XDM.Core.Lib.UI;

namespace XDM.Core.Lib.Common
{
    public interface INewVideoDownloadDialog : IFileSelectable
    {
        void DisposeWindow();
        void Invoke(Action callback);
        void ShowWindow();
        string FileSize { get; set; }
        public AuthenticationInfo? Authentication { get; set; }
        public ProxyInfo? Proxy { get; set; }
        public int SpeedLimit { get; set; }
        public bool EnableSpeedLimit { get; set; }

        event EventHandler DownloadClicked, CancelClicked, DestroyEvent, QueueSchedulerClicked;
        event EventHandler<DownloadLaterEventArgs> DownloadLaterClicked;
    }
}
