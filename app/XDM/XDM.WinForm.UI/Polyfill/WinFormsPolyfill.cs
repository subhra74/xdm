using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

using System.Windows.Forms;

namespace XDM.WinForm.UI
{
    internal static class WinFormsPolyfill
    {
        public static int LogicalToDeviceUnits(int value)
        {
            return DpiHelper.LogicalToDeviceUnits(value, DpiHelper.DeviceDpi);
        }
    }
}
