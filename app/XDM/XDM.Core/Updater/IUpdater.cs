using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using XDM.Core.Updater;

namespace XDM.Core.Updater
{
    public interface IUpdater
    {
        public void StartUpdate(IList<UpdateInfo> updates);
        public void CancelUpdate();
    }
}
