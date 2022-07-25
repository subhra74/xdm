using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using TraceLog;
using Translations;
using XDM.Core;
using XDM.Core.DataAccess;
using XDM.Core.Downloader;
using XDM.Core.UI;
using XDM.Core.Util;

namespace XDMApp
{
    internal static class UIActions
    {
        public static void DeleteDownloads(bool inProgressOnly, IAppWinPeer peer, IAppService app, Action<bool>? callback)
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
                            AppDB.Instance.Downloads.RemoveDownloadById(item.DownloadEntry.Id);

                        }
                    }
                    callback?.Invoke(true);
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
                        AppDB.Instance.Downloads.RemoveDownloadById(selectedRow.DownloadEntry.Id);
                    }
                    callback?.Invoke(false);
                }
            }
        }

        public static void OnDblClick(IAppWinPeer peer, IAppService app)
        {
            if (peer.IsInProgressViewSelected)
            {
                ShowSeletectedItemProperties(peer, app);
            }
            else
            {
                if (Config.Instance.DoubleClickOpenFile)
                {
                    OpenSelectedFile(peer);
                }
                else
                {
                    OpenSelectedFolder(peer);
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

        public static void StopSelectedDownloads(IAppWinPeer peer, IAppService app)
        {
            app.StopDownloads(peer.SelectedInProgressRows.Select(x => x.DownloadEntry.Id), true);
        }

        public static void ResumeDownloads(IAppWinPeer peer, IAppService app)
        {
            var idDict = new Dictionary<string, BaseDownloadEntry>();
            var list = peer.SelectedInProgressRows;
            foreach (var item in list)
            {
                idDict[item.DownloadEntry.Id] = item.DownloadEntry;
            }
            app.ResumeDownload(idDict);
        }

        public static void MoveToQueue(IAppWinPeer peer, IAppController appUI)
        {
            var selectedIds = peer.SelectedInProgressRows?.Select(x => x.DownloadEntry.Id)?.ToArray() ?? new string[0];
            MoveToQueue(peer, appUI, selectedIds);
        }

        public static void MoveToQueue(IAppWinPeer peer, IAppController appUI, string[] selectedIds, bool prompt = false, Action? callback = null)
        {
            if (prompt && !peer.Confirm(peer, "Add to queue?"))
            {
                return;
            }
            using var queueSelectionDialog = peer.CreateQueueSelectionDialog();
            queueSelectionDialog.SetData(QueueManager.Queues.Select(q => q.Name), QueueManager.Queues.Select(q => q.ID), selectedIds);
            queueSelectionDialog.ManageQueuesClicked += (_, _) =>
            {
                appUI.ShowQueueWindow(peer);
            };
            queueSelectionDialog.QueueSelected += (s, e) =>
            {
                //var index = e.SelectedQueueIndex;
                //var queueId = QueueManager.Queues[index].ID;
                var downloadIds = e.DownloadIds;
                QueueManager.AddDownloadsToQueue(e.SelectedQueueId, downloadIds.ToArray());
            };
            queueSelectionDialog.ShowWindow(peer);
        }

        public static void SaveAs(IAppWinPeer peer, IAppService app)
        {
            var rows = peer.SelectedInProgressRows;
            if (rows == null || rows.Count < 1) return;
            var item = rows[0].DownloadEntry;
            var file = peer.SaveFileDialog(Path.Combine(item.TargetDir ?? Helpers.GetDownloadFolderByFileName(item.Name), item.Name), null, null);
            if (file == null)
            {
                return;
            }
            Log.Debug("folder: " + Path.GetDirectoryName(file) + " file: " + Path.GetFileName(file));
            app.RenameDownload(item.Id, Path.GetDirectoryName(file)!, Path.GetFileName(file));
        }

        public static void RefreshLink(IAppWinPeer peer, IAppService app)
        {
            var selected = peer.SelectedInProgressRows;
            if (selected == null || selected.Count == 0) return;
            peer.ShowRefreshLinkDialog(selected[0].DownloadEntry, app);
        }

        public static void ShowProgressWindow(IAppWinPeer peer, IAppService app)
        {
            var selected = peer.SelectedInProgressRows;
            if (selected == null || selected.Count == 0) return;
            app.ShowProgressWindow(selected[0].DownloadEntry.Id);
        }

        public static void CopyURL1(IAppWinPeer peer, IAppService app)
        {
            var selected = peer.SelectedInProgressRows;
            if (selected == null || selected.Count == 0) return;
            var url = app.GetPrimaryUrl(selected[0].DownloadEntry);
            if (url != null)
            {
                peer.SetClipboardText(url);
            }
        }

        public static void CopyURL2(IAppWinPeer peer, IAppService app)
        {
            var selected = peer.SelectedFinishedRows;
            if (selected == null || selected.Count == 0) return;
            var url = app.GetPrimaryUrl(selected[0].DownloadEntry);
            if (url != null)
            {
                peer.SetClipboardText(url);
            }
        }

        public static void ShowSeletectedItemProperties(IAppWinPeer peer, IAppService app)
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
                switch (ent.DownloadType)
                {
                    case "Http":
                        var s = DownloadStateStore.LoadSingleSourceHTTPDownloaderState(ent.Id);
                        state = new()
                        {
                            Headers = s.Headers,
                            Cookies = s.Cookies
                        };
                        break;
                    case "Dash":
                        var d = DownloadStateStore.LoadDualSourceHTTPDownloaderState(ent.Id);
                        state = new()
                        {
                            Headers1 = d.Headers1,
                            Headers2 = d.Headers2,
                            Cookies2 = d.Cookies2,
                            Cookies1 = d.Cookies1
                        };
                        break;
                    case "Hls":
                        var h = DownloadStateStore.LoadMultiSourceHLSDownloadState(ent.Id);
                        state = new()
                        {
                            Headers = h.Headers,
                            Cookies = h.Cookies
                        };
                        break;
                    case "Mpd-Dash":
                        var m = DownloadStateStore.LoadMultiSourceDASHDownloadState(ent.Id);
                        state = new()
                        {
                            Headers = m.Headers,
                            Cookies = m.Cookies
                        };
                        break;
                }
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

        public static void RestartDownload(IAppWinPeer peer, IAppService app)
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
