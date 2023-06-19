using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading;
using TraceLog;
using Translations;
using XDM.Core.Util;

namespace XDM.Core.Updater
{
    public static class AppUpdater
    {
        private static Timer UpdateCheckTimer;
        public static IList<UpdateInfo>? Updates { get; private set; }
        public static bool IsAppUpdateAvailable => Updates?.Any(u => !u.IsExternal) ?? false;
        public static bool IsComponentUpdateAvailable => Updates?.Any(u => u.IsExternal) ?? false;
        public static string ComponentUpdateText => GetUpdateText();
        public static string UpdatePage =>
            $"{Links.AppUpdateCheckerUrl}?v={ApplicationContext.CoreService.AppVerion}&p={ApplicationContext.CoreService.AppPlatform}";

        public static void QueryNewVersion()
        {
            UpdateCheckTimer = new(
                callback: a => CheckForUpdate(),
                state: null,
                dueTime: TimeSpan.FromSeconds(5),
                period: TimeSpan.FromHours(3));
        }

        private static void CheckForUpdate()
        {
            try
            {
                Log.Debug("Checking for updates...");
                if (UpdateChecker.GetAppUpdates(ApplicationContext.CoreService.AppVerion, out IList<UpdateInfo> upd, out bool firstUpdate))
                {
                    Updates = upd;
                    if (upd != null && upd.Count > 0)
                    {
                        ApplicationContext.Application.ShowUpdateAvailableNotification();
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "CheckForUpdate");
            }
        }

        private static string GetUpdateText()
        {
            if (Updates == null || Updates.Count < 1) return TextResource.GetText("MSG_NO_UPDATE");
            var text = new StringBuilder();
            var size = 0L;
            text.Append("Update(s) available: " + Environment.NewLine);
            foreach (var update in Updates)
            {
                text.Append(update.Name + " " + update.TagName + Environment.NewLine);
                size += update.Size;
            }
            text.Append(Environment.NewLine + "Total download: " + FormattingHelper.FormatSize(size) + Environment.NewLine + Environment.NewLine);
            text.Append("Would you like to continue?");
            return text.ToString();
        }
    }
}
