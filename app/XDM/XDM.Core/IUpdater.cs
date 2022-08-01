using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;


namespace XDM.Core
{
    public interface IUpdater
    {
        public void StartUpdate(IList<UpdateInfo> updates);
        public void CancelUpdate();
    }
}
