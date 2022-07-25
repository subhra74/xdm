using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using XDM.Core.Lib.Common;

namespace XDM.Core.Lib.UI
{
    public interface ISettingsPage
    {
        void PopulateUI();
        void UpdateConfig();

        public IAppService App { get; set; }
    }
}
