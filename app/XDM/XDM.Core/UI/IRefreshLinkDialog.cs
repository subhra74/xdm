using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;


namespace XDM.Core
{
    public interface IRefreshLinkDialog
    {
        event EventHandler? WatchingStopped;

        void ShowWindow();

        void LinkReceived();
    }
}
