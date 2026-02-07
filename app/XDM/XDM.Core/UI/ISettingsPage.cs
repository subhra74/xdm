using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using XDM.Core;

namespace XDM.Core.UI
{
    public interface ISettingsPage
    {
        void PopulateUI();
        void UpdateConfig();
    }
}
