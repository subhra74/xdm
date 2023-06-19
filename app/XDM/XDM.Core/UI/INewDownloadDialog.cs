using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using XDM.Core.UI;

namespace XDM.Core.UI
{
    public interface INewDownloadDialog : IFileSelectable
    {
        void SetFileSizeText(string text);
        void DisposeWindow();
        void Invoke(Action callback);
        void ShowWindow();
        void ShowMessageBox(string message);
        string Url { get; set; }
        AuthenticationInfo? Authentication { get; set; }
        ProxyInfo? Proxy { get; set; }
        int SpeedLimit { get; set; }
        bool EnableSpeedLimit { get; set; }
        event EventHandler DownloadClicked, DestroyEvent,
            UrlChangedEvent, UrlBlockedEvent, QueueSchedulerClicked;
        event EventHandler<DownloadLaterEventArgs> DownloadLaterClicked;
    }
}
