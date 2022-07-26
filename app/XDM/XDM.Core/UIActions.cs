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

namespace XDM.Core
{
    internal static class UIActions
    {
        public static void DeleteDownloads(bool inProgressOnly, Action<bool>? callback)
        {
            if (inProgressOnly)
            {
                var selectedItems = ApplicationContext.MainWindow.SelectedInProgressRows;
                ApplicationContext.CoreService.StopDownloads(selectedItems.Select(x => x.DownloadEntry.Id));
                if (ApplicationContext.MainWindow.Confirm(ApplicationContext.MainWindow, TextResource.GetText("DEL_SEL_TEXT")))
                {
                    foreach (var item in selectedItems)
                    {
                        if (item != null)
                        {
                            ApplicationContext.MainWindow.Delete(item);
                            ApplicationContext.CoreService.RemoveDownload(item.DownloadEntry, false);
                            AppDB.Instance.Downloads.RemoveDownloadById(item.DownloadEntry.Id);

                        }
                    }
                    callback?.Invoke(true);
                }
            }
            else
            {
                var selectedRows = ApplicationContext.MainWindow.SelectedFinishedRows;
                ApplicationContext.MainWindow.ConfirmDelete(TextResource.GetText("DEL_SEL_TEXT"),
                    out bool approved, out bool deleteFiles);
                if (approved)
                {
                    foreach (var selectedRow in selectedRows)
                    {
                        ApplicationContext.CoreService.RemoveDownload(selectedRow.DownloadEntry, deleteFiles);
                        ApplicationContext.MainWindow.Delete(selectedRow);
                        AppDB.Instance.Downloads.RemoveDownloadById(selectedRow.DownloadEntry.Id);
                    }
                    callback?.Invoke(false);
                }
            }
        }

        public static void OnDblClick()
        {
            if (ApplicationContext.MainWindow.IsInProgressViewSelected)
            {
                ShowSeletectedItemProperties();
            }
            else
            {
                if (Config.Instance.DoubleClickOpenFile)
                {
                    OpenSelectedFile();
                }
                else
                {
                    OpenSelectedFolder();
                }
            }
        }

        public static void OpenSelectedFolder()
        {
            var selectedRows = ApplicationContext.MainWindow.SelectedFinishedRows;
            if (selectedRows.Count > 0)
            {
                var row = selectedRows[0];
                var ent = row.DownloadEntry;
                //Log.Debug("Open folder: " + ent.TargetDir);
                if (!PlatformHelper.OpenFolder(ent.TargetDir, ent.Name))
                {
                    ApplicationContext.MainWindow.ShowMessageBox(ApplicationContext.MainWindow, TextResource.GetText("ERR_MSG_FILE_NOT_FOUND_MSG"));
                }
                return;
            }
            ApplicationContext.MainWindow.ShowMessageBox(ApplicationContext.MainWindow, TextResource.GetText("NO_ITEM_SELECTED"));
        }

        public static void OpenSelectedFile()
        {
            var selectedRows = ApplicationContext.MainWindow.SelectedFinishedRows;
            if (selectedRows.Count > 0)
            {
                var row = selectedRows[0];
                var ent = row.DownloadEntry;
                if (!string.IsNullOrEmpty(ent.TargetDir))
                {
                    var file = Path.Combine(ent.TargetDir, ent.Name);
                    //Log.Debug("Open: " + file);
                    if (!PlatformHelper.OpenFile(file))
                    {
                        ApplicationContext.MainWindow.ShowMessageBox(ApplicationContext.MainWindow, TextResource.GetText("ERR_MSG_FILE_NOT_FOUND_MSG"));
                    }
                    return;
                }
                else
                {
                    Log.Debug("Path is null");
                }
            }
            ApplicationContext.MainWindow.ShowMessageBox(ApplicationContext.MainWindow, TextResource.GetText("NO_ITEM_SELECTED"));
        }

        public static void StopSelectedDownloads()
        {
            ApplicationContext.CoreService.StopDownloads(ApplicationContext.MainWindow.SelectedInProgressRows.Select(x => x.DownloadEntry.Id), true);
        }

        public static void ResumeDownloads()
        {
            var idDict = new Dictionary<string, BaseDownloadEntry>();
            var list = ApplicationContext.MainWindow.SelectedInProgressRows;
            foreach (var item in list)
            {
                idDict[item.DownloadEntry.Id] = item.DownloadEntry;
            }
            ApplicationContext.CoreService.ResumeDownload(idDict);
        }

        public static void MoveToQueue()
        {
            var selectedIds = ApplicationContext.MainWindow.SelectedInProgressRows?.Select(x => x.DownloadEntry.Id)?.ToArray() ?? new string[0];
            MoveToQueue(selectedIds);
        }

        public static void MoveToQueue(string[] selectedIds, bool prompt = false, Action? callback = null)
        {
            if (prompt && !ApplicationContext.MainWindow.Confirm(ApplicationContext.MainWindow, "Add to queue?"))
            {
                return;
            }
            using var queueSelectionDialog = ApplicationContext.MainWindow.CreateQueueSelectionDialog();
            queueSelectionDialog.SetData(QueueManager.Queues.Select(q => q.Name), QueueManager.Queues.Select(q => q.ID), selectedIds);
            queueSelectionDialog.ManageQueuesClicked += (_, _) =>
            {
                ApplicationContext.Application.ShowQueueWindow(ApplicationContext.MainWindow);
            };
            queueSelectionDialog.QueueSelected += (s, e) =>
            {
                //var index = e.SelectedQueueIndex;
                //var queueId = QueueManager.Queues[index].ID;
                var downloadIds = e.DownloadIds;
                QueueManager.AddDownloadsToQueue(e.SelectedQueueId, downloadIds.ToArray());
            };
            queueSelectionDialog.ShowWindow();
        }

        public static void SaveAs()
        {
            var rows = ApplicationContext.MainWindow.SelectedInProgressRows;
            if (rows == null || rows.Count < 1) return;
            var item = rows[0].DownloadEntry;
            var file = ApplicationContext.MainWindow.SaveFileDialog(Path.Combine(item.TargetDir ?? FileHelper.GetDownloadFolderByFileName(item.Name), item.Name), null, null);
            if (file == null)
            {
                return;
            }
            Log.Debug("folder: " + Path.GetDirectoryName(file) + " file: " + Path.GetFileName(file));
            ApplicationContext.CoreService.RenameDownload(item.Id, Path.GetDirectoryName(file)!, Path.GetFileName(file));
        }

        public static void RefreshLink()
        {
            var selected = ApplicationContext.MainWindow.SelectedInProgressRows;
            if (selected == null || selected.Count == 0) return;
            ApplicationContext.MainWindow.ShowRefreshLinkDialog(selected[0].DownloadEntry);
        }

        public static void ShowProgressWindow()
        {
            var selected = ApplicationContext.MainWindow.SelectedInProgressRows;
            if (selected == null || selected.Count == 0) return;
            ApplicationContext.CoreService.ShowProgressWindow(selected[0].DownloadEntry.Id);
        }

        public static void CopyURL1()
        {
            var selected = ApplicationContext.MainWindow.SelectedInProgressRows;
            if (selected == null || selected.Count == 0) return;
            var url = ApplicationContext.CoreService.GetPrimaryUrl(selected[0].DownloadEntry);
            if (url != null)
            {
                ApplicationContext.MainWindow.SetClipboardText(url);
            }
        }

        public static void CopyURL2()
        {
            var selected = ApplicationContext.MainWindow.SelectedFinishedRows;
            if (selected == null || selected.Count == 0) return;
            var url = ApplicationContext.CoreService.GetPrimaryUrl(selected[0].DownloadEntry);
            if (url != null)
            {
                ApplicationContext.MainWindow.SetClipboardText(url);
            }
        }

        public static void ShowSeletectedItemProperties()
        {
            BaseDownloadEntry? ent = null;
            if (ApplicationContext.MainWindow.IsInProgressViewSelected)
            {
                var rows = ApplicationContext.MainWindow.SelectedInProgressRows;
                if (rows.Count > 0)
                {
                    ent = rows[0].DownloadEntry;
                }
            }
            else
            {
                var rows = ApplicationContext.MainWindow.SelectedFinishedRows;
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
            ApplicationContext.MainWindow.ShowPropertiesDialog(ent, state);
        }

        public static void CopyFile()
        {
            var selected = ApplicationContext.MainWindow.SelectedFinishedRows;
            if (selected == null || selected.Count == 0) return;
            var entry = selected[0].DownloadEntry;
            var file = Path.Combine(entry.TargetDir, entry.Name);
            if (File.Exists(file))
            {
                ApplicationContext.MainWindow.SetClipboardFile(file);
            }
            else
            {
                ApplicationContext.MainWindow.ShowMessageBox(ApplicationContext.MainWindow, TextResource.GetText("ERR_MSG_FILE_NOT_FOUND_MSG"));
            }
        }

        public static void RestartDownload()
        {
            BaseDownloadEntry? ent = null;
            if (ApplicationContext.MainWindow.IsInProgressViewSelected)
            {
                var rows = ApplicationContext.MainWindow.SelectedInProgressRows;
                if (rows.Count > 0)
                {
                    ent = rows[0].DownloadEntry;
                }
            }
            else
            {
                var rows = ApplicationContext.MainWindow.SelectedFinishedRows;
                if (rows.Count > 0)
                {
                    ent = rows[0].DownloadEntry;
                }
            }
            if (ent == null) return;
            ApplicationContext.CoreService.RestartDownload(ent);
        }
    }
}
