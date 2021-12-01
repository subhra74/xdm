using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using XDM.Core.Lib.UI;

namespace XDM.Core.Lib.Common
{
    public interface INewDownloadDialogSkeleton : IFileSelectable
    {
        void SetFileSizeText(string text);
        void DisposeWindow();
        void Invoke(Action callback);
        void ShowWindow();
        string Url { get; set; }
        AuthenticationInfo? Authentication { get; set; }
        ProxyInfo? Proxy { get; set; }
        int SpeedLimit { get; set; }
        bool EnableSpeedLimit { get; set; }
        event EventHandler DownloadClicked,
            CancelClicked, DestroyEvent, BlockHostEvent,
            UrlChangedEvent, UrlBlockedEvent, QueueSchedulerClicked;
        event EventHandler<DownloadLaterEventArgs> DownloadLaterClicked;
    }
}
