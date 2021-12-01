using System.Windows.Forms;
using XDM.Core.Lib.Common;

namespace XDM.WinForm.UI
{
    internal static class AdvancedDialogHelper
    {
        internal static void Show(
            ref AuthenticationInfo? Authentication,
            ref ProxyInfo? Proxy,
            ref bool EnableSpeedLimit,
            ref int SpeedLimit,
            IWin32Window window)
        {
            using var dlg = new AdvancedDownloadDialog();
            dlg.Authentication = Authentication;
            dlg.Proxy = Proxy;
            dlg.EnableSpeedLimit = EnableSpeedLimit;
            dlg.SpeedLimit = SpeedLimit;
            if (dlg.ShowDialog(window) == DialogResult.OK)
            {
                Authentication = dlg.Authentication;
                Proxy = dlg.Proxy;
                EnableSpeedLimit = dlg.EnableSpeedLimit;
                SpeedLimit = dlg.SpeedLimit;
            }
        }
    }
}
