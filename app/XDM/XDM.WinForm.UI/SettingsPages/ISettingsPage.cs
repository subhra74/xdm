using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

using XDM.Core.Lib.Common;

namespace XDM.WinForm.UI.SettingsPages
{
    internal interface ISettingsPage
    {
        void PopulateUI();
        void UpdateConfig();
    }
}
