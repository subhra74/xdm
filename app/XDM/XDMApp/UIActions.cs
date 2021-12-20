using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using TraceLog;
using Translations;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Common.Segmented;
using XDM.Core.Lib.UI;
using XDM.Core.Lib.Util;

namespace XDMApp
{
    internal static class UIActions
    {
        public static void DeleteDownloads(bool inProgressOnly, IAppWinPeer peer, IApp app, Action<bool> callback)
        {
            if (inProgressOnly)
            {
                var selectedItems = peer.SelectedInProgressRows;
                app.StopDownloads(selectedItems.Select(x => x.DownloadEntry.Id));
                if (peer.Confirm(peer, TextResource.GetText("DEL_SEL_TEXT")))
                {
                    foreach (var item in selectedItems)
                    {
                        if (item != null)
                        {
                            peer.Delete(item);
                            app.RemoveDownload(item.DownloadEntry, false);
                        }
                    }
                    callback.Invoke(true);
                }
            }
            else
            {
                var selectedRows = peer.SelectedFinishedRows;
                peer.ConfirmDelete(TextResource.GetText("DEL_SEL_TEXT"),
                    out bool approved, out bool deleteFiles);
                if (approved)
                {
                    foreach (var selectedRow in selectedRows)
                    {
                        app.RemoveDownload(selectedRow.DownloadEntry, deleteFiles);
                        peer.Delete(selectedRow);
                    }
                    callback.Invoke(false);
                }
            }
        }

        public static void OpenSelectedFolder(IAppWinPeer peer)
        {
            var selectedRows = peer.SelectedFinishedRows;
            if (selectedRows.Count > 0)
            {
                var row = selectedRows[0];
                var ent = row.DownloadEntry;
                //Log.Debug("Open folder: " + ent.TargetDir);
                if (!Helpers.OpenFolder(ent.TargetDir, ent.Name))
                {
                    peer.ShowMessageBox(peer, TextResource.GetText("ERR_MSG_FILE_NOT_FOUND_MSG"));
                }
                return;
            }
            peer.ShowMessageBox(peer, TextResource.GetText("NO_ITEM_SELECTED"));
        }

        public static void OpenSelectedFile(IAppWinPeer peer)
        {
            var selectedRows = peer.SelectedFinishedRows;
            if (selectedRows.Count > 0)
            {
                var row = selectedRows[0];
                var ent = row.DownloadEntry;
                if (!string.IsNullOrEmpty(ent.TargetDir))
                {
                    var file = Path.Combine(ent.TargetDir, ent.Name);
                    //Log.Debug("Open: " + file);
                    if (!Helpers.OpenFile(file))
                    {
                        peer.ShowMessageBox(peer, TextResource.GetText("ERR_MSG_FILE_NOT_FOUND_MSG"));
                    }
                    return;
                }
                else
                {
                    Log.Debug("Path is null");
                }
            }
            peer.ShowMessageBox(peer, TextResource.GetText("NO_ITEM_SELECTED"));
        }

        public static void StopSelectedDownloads(IAppWinPeer peer, IApp app)
        {
            app.StopDownloads(peer.SelectedInProgressRows.Select(x => x.DownloadEntry.Id), true);
        }

        public static void ResumeDownloads(IAppWinPeer peer, IApp app)
        {
            var idDict = new Dictionary<string, BaseDownloadEntry>();
            var list = peer.SelectedInProgressRows;
            foreach (var item in list)
            {
                idDict[item.DownloadEntry.Id] = item.DownloadEntry;
            }
            app.ResumeDownload(idDict);
        }

        public static void SaveAs(IAppWinPeer peer, IApp app)
        {
            var rows = peer.SelectedInProgressRows;
            if (rows == null || rows.Count < 1) return;
            var item = rows[0].DownloadEntry;
            var file = peer.SaveFileDialog(Path.Combine(item.TargetDir ?? Helpers.GetDownloadFolderByFileName(item.Name), item.Name));
            if (file == null)
            {
                return;
            }
            Log.Debug("folder: " + Path.GetDirectoryName(file) + " file: " + Path.GetFileName(file));
            app.RenameDownload(item.Id, Path.GetDirectoryName(file)!, Path.GetFileName(file));
        }

        public static void RefreshLink(IAppWinPeer peer, IApp app)
        {
            var selected = peer.SelectedInProgressRows;
            if (selected == null || selected.Count == 0) return;
            peer.ShowRefreshLinkDialog(selected[0].DownloadEntry, app);
        }

        public static void ShowProgressWindow(IAppWinPeer peer, IApp app)
        {
            var selected = peer.SelectedInProgressRows;
            if (selected == null || selected.Count == 0) return;
            app.ShowProgressWindow(selected[0].DownloadEntry.Id);
        }

        public static void CopyURL1(IAppWinPeer peer, IApp app)
        {
            var selected = peer.SelectedInProgressRows;
            if (selected == null || selected.Count == 0) return;
            var url = app.GetPrimaryUrl(selected[0].DownloadEntry);
            if (url != null)
            {
                peer.SetClipboardText(url);
            }
        }

        public static void CopyURL2(IAppWinPeer peer, IApp app)
        {
            var selected = peer.SelectedFinishedRows;
            if (selected == null || selected.Count == 0) return;
            var url = app.GetPrimaryUrl(selected[0].DownloadEntry);
            if (url != null)
            {
                peer.SetClipboardText(url);
            }
        }

        public static void ShowSeletectedItemProperties(IAppWinPeer peer, IApp app)
        {
            BaseDownloadEntry? ent = null;
            if (peer.IsInProgressViewSelected)
            {
                var rows = peer.SelectedInProgressRows;
                if (rows.Count > 0)
                {
                    ent = rows[0].DownloadEntry;
                }
            }
            else
            {
                var rows = peer.SelectedFinishedRows;
                if (rows.Count > 0)
                {
                    ent = rows[0].DownloadEntry;
                }
            }
            if (ent == null) return;

            ShortState? state = null;
            try
            {
                var stateFile = Path.Combine(Config.DataDir, ent.Id + ".state");
                var bytes = File.ReadAllBytes(stateFile);
                switch (ent.DownloadType)
                {
                    case "Http":
                        var s = DownloadStateStore.SingleSourceHTTPDownloaderStateFromBytes(bytes);
                        state = new()
                        {
                            Headers = s.Headers,
                            Cookies = s.Cookies
                        };
                        break;
                    case "Dash":
                        var d = DownloadStateStore.DualSourceHTTPDownloaderStateFromBytes(bytes);
                        state = new()
                        {
                            Headers1 = d.Headers1,
                            Headers2 = d.Headers2,
                            Cookies2 = d.Cookies2,
                            Cookies1 = d.Cookies1
                        };
                        break;
                    case "Hls":
                        var h = DownloadStateStore.MultiSourceHLSDownloadStateFromBytes(bytes);
                        state = new()
                        {
                            Headers = h.Headers,
                            Cookies = h.Cookies
                        };
                        break;
                    case "Mpd-Dash":
                        var m = DownloadStateStore.MultiSourceDASHDownloadStateFromBytes(bytes);
                        state = new()
                        {
                            Headers = m.Headers,
                            Cookies = m.Cookies
                        };
                        break;
                }

                state = JsonConvert.DeserializeObject<ShortState>(
                    File.ReadAllText(stateFile),
                    new JsonSerializerSettings
                    {
                        MissingMemberHandling = MissingMemberHandling.Ignore,
                    });
            }
            catch { }
            peer.ShowPropertiesDialog(ent, state);
        }

        public static void CopyFile(IAppWinPeer peer)
        {
            var selected = peer.SelectedFinishedRows;
            if (selected == null || selected.Count == 0) return;
            var entry = selected[0].DownloadEntry;
            var file = Path.Combine(entry.TargetDir, entry.Name);
            if (File.Exists(file))
            {
                peer.SetClipboardFile(file);
            }
            else
            {
                peer.ShowMessageBox(peer, TextResource.GetText("ERR_MSG_FILE_NOT_FOUND_MSG"));
            }
        }

        public static void RestartDownload(IAppWinPeer peer, IApp app)
        {
            BaseDownloadEntry? ent = null;
            if (peer.IsInProgressViewSelected)
            {
                var rows = peer.SelectedInProgressRows;
                if (rows.Count > 0)
                {
                    ent = rows[0].DownloadEntry;
                }
            }
            else
            {
                var rows = peer.SelectedFinishedRows;
                if (rows.Count > 0)
                {
                    ent = rows[0].DownloadEntry;
                }
            }
            if (ent == null) return;
            app.RestartDownload(ent);
        }

        //public static void ScheduleDownload(IAppWinPeer peer, IApp app)
        //{
        //    var selected = peer.SelectedInProgressRows;
        //    if (selected == null || selected.Count == 0) return;
        //    var schedule = peer.ShowSchedulerDialog(selected[0].DownloadEntry.Schedule.GetValueOrDefault());
        //    foreach (var row in selected)
        //    {
        //        var ent = row.DownloadEntry;
        //        ent.Schedule = schedule;
        //    }
        //}
    }
}
