using System;
using System.Collections.Generic;
using System.Text;

namespace XDM.Core.UI
{
    public static class SpeedLimiterUIController
    {
        public static void ShowGlobalSpeedLimiterWindow(ISpeedLimiterWindow window)
        {
            window.OkClicked += Window_OkClicked;
            window.ShowWindow();
        }

        private static void Window_OkClicked(object? sender, EventArgs e)
        {
            var window = sender as ISpeedLimiterWindow;
            if (window == null) return;
            window.OkClicked -= Window_OkClicked;
            var speedLimitEnabled = window.EnableSpeedLimit;
            var defaultSpeedLimit = window.SpeedLimit;
            Config.Instance.EnableSpeedLimit = speedLimitEnabled ? defaultSpeedLimit > 0 : false;
            Config.Instance.DefaltDownloadSpeed = defaultSpeedLimit > 0 ? defaultSpeedLimit : 0;
            Config.SaveConfig();
            ApplicationContext.BroadcastConfigChange();
        }
    }
}
