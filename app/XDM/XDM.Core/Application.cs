using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using TraceLog;
using Translations;
using XDM.Core.UI;
using XDM.Core;
using XDM.Core.DataAccess;
using XDM.Core.Downloader;
using XDM.Core.Util;

namespace XDM.Core
{
    public class Application : IApplication
    {
        private delegate void UpdateItemCallBack(string id, string targetFileName, long size);
        private Action<string, int, double, long> updateProgressAction;
        private long lastProgressUpdate = 0;
        public event EventHandler WindowLoaded;

        public Application()
        {
            this.updateProgressAction = new Action<string, int, double, long>(this.UpdateProgressOnUI);
            AttachedEventHandler();
            this.LoadDownloadList();
            UpdateToolbarButtonState();
        }

        public void AddItemToTop(
            string id,
            string targetFileName,
            DateTime date,
            long fileSize,
            string type,
            FileNameFetchMode fileNameFetchMode,
            string primaryUrl,
            DownloadStartType startType,
            AuthenticationInfo? authentication,
            ProxyInfo? proxyInfo,
            int maxSpeedLimit)
        {
            var downloadEntry = new InProgressDownloadEntry
            {
                Name = targetFileName,
                DateAdded = date,
                DownloadType = type,
                Id = id,
                Progress = 0,
                Size = fileSize,
                Status = startType == DownloadStartType.Waiting ? DownloadStatus.Waiting : DownloadStatus.Stopped,
                TargetDir = "",
                PrimaryUrl = primaryUrl,
                Authentication = authentication,
                Proxy = proxyInfo,
                MaxSpeedLimitInKiB = maxSpeedLimit,
            };
            AppDB.Instance.Downloads.AddNewDownload(downloadEntry);

            RunOnUiThread(() =>
            {
                AppInstance.MainWindow.AddToTop(downloadEntry);
                AppInstance.MainWindow.SwitchToInProgressView();
                AppInstance.MainWindow.ClearInProgressViewSelection();
                UpdateToolbarButtonState();
            });
        }

        public bool Confirm(object? window, string text)
        {
            return AppInstance.MainWindow.Confirm(window, text);
        }

        public IDownloadCompleteDialog CreateDownloadCompleteDialog()
        {
            return AppInstance.MainWindow.CreateDownloadCompleteDialog(AppInstance.Core);
        }

        public INewDownloadDialogSkeleton CreateNewDownloadDialog(bool empty)
        {
            return AppInstance.MainWindow.CreateNewDownloadDialog(empty);
        }

        public INewVideoDownloadDialog CreateNewVideoDialog()
        {
            return AppInstance.MainWindow.CreateNewVideoDialog();
        }

        public IProgressWindow CreateProgressWindow(string downloadId)
        {
            return AppInstance.MainWindow.CreateProgressWindow(downloadId, AppInstance.Core, this);
        }

        public void DownloadCanelled(string id)
        {
            DownloadFailed(id);
        }

        public void DownloadFailed(string id)
        {
            AppDB.Instance.Downloads.UpdateDownloadStatus(id, DownloadStatus.Stopped);
            RunOnUiThread(() =>
            {
                CallbackActions.DownloadFailed(id, AppInstance.MainWindow);
                UpdateToolbarButtonState();
            });
        }

        public void DownloadFinished(string id, long finalFileSize, string filePath)
        {
            if (!string.IsNullOrEmpty(filePath))
            {
                var name = Path.GetFileName(filePath);
                var folder = Path.GetDirectoryName(filePath);
                AppDB.Instance.Downloads.MarkAsFinished(id, finalFileSize, name, folder);
            }

            Log.Debug("Final file name: " + filePath);
            var downloadEntry = AppDB.Instance.Downloads.GetDownloadById(id);
            if (downloadEntry != null)
            {
                var finishedEntry = new FinishedDownloadEntry
                {
                    Name = Path.GetFileName(filePath),
                    Id = downloadEntry.Id,
                    DateAdded = downloadEntry.DateAdded,
                    Size = downloadEntry.Size > 0 ? downloadEntry.Size : finalFileSize,
                    DownloadType = downloadEntry.DownloadType,
                    TargetDir = Path.GetDirectoryName(filePath)!,
                    PrimaryUrl = downloadEntry.PrimaryUrl,
                    Authentication = downloadEntry.Authentication,
                    Proxy = downloadEntry.Proxy
                };
                AppDB.Instance.Downloads.UpdateDownloadEntry(finishedEntry);

                RunOnUiThread(() =>
                {
                    var download = AppInstance.MainWindow.FindInProgressItem(id);
                    if (download == null) return;

                    AppInstance.MainWindow.AddToTop(finishedEntry);
                    AppInstance.MainWindow.Delete(download);

                    QueueManager.RemoveFinishedDownload(download.DownloadEntry.Id);

                    if (AppInstance.Core.ActiveDownloadCount == 0 && AppInstance.MainWindow.IsInProgressViewSelected)
                    {
                        Log.Debug("switching to finished listview");
                        AppInstance.MainWindow.SwitchToFinishedView();
                    }
                });
            }
        }

        public void DownloadStarted(string id)
        {
            RunOnUiThread(() =>
            {
                CallbackActions.DownloadStarted(id, AppInstance.MainWindow);
                UpdateToolbarButtonState();
            });
        }

        public IEnumerable<InProgressDownloadEntry> GetAllInProgressDownloads()
        {
            return AppInstance.MainWindow.InProgressDownloads;
        }

        public InProgressDownloadEntry? GetInProgressDownloadEntry(string downloadId)
        {
            return AppInstance.MainWindow.FindInProgressItem(downloadId)?.DownloadEntry;
        }

        public string? GetUrlFromClipboard()
        {
            var text = AppInstance.MainWindow.GetUrlFromClipboard();
            if (Helpers.IsUriValid(text))
            {
                return text;
            }
            return null;
        }

        public AuthenticationInfo? PromtForCredentials(string message)
        {
            return AppInstance.MainWindow.PromtForCredentials(message);
        }

        public void RenameFileOnUI(string id, string folder, string file)
        {
            if (!AppDB.Instance.Downloads.UpdateNameAndFolder(id, file, folder))
            {
                Log.Debug("RenameFileOnUI::failed");
            }
            RunOnUiThread(() =>
            {
                var downloadEntry = AppInstance.MainWindow.FindInProgressItem(id);
                if (downloadEntry == null) return;
                if (file != null)
                {
                    downloadEntry.Name = file;
                }
                if (folder != null)
                {
                    downloadEntry.DownloadEntry.TargetDir = folder;
                }
                //this.SaveInProgressList();
            });
        }

        public void ResumeDownload(string downloadId)
        {
            var idDict = new Dictionary<string, BaseDownloadEntry>();
            var download = AppInstance.MainWindow.FindInProgressItem(downloadId);
            if (download == null) return;
            idDict[download.DownloadEntry.Id] = download.DownloadEntry;
            AppInstance.Core.ResumeDownload(idDict);
        }

        public void RunOnUiThread(Action action)
        {
            AppInstance.MainWindow.RunOnUIThread(action);
        }

        public void SetDownloadStatusWaiting(string id)
        {
            RunOnUiThread(() =>
            {
                var download = AppInstance.MainWindow.FindInProgressItem(id);
                if (download == null) return;
                download.Status = DownloadStatus.Waiting;
                UpdateToolbarButtonState();
            });
        }

        public void ShowUpdateAvailableNotification()
        {
            RunOnUiThread(() =>
            {
                AppInstance.MainWindow.ShowUpdateAvailableNotification();
            });
        }

        public void ShowDownloadCompleteDialog(string file, string folder)
        {
            RunOnUiThread(() =>
            {
                DownloadCompleteDialogHelper.ShowDialog(AppInstance.Core, CreateDownloadCompleteDialog(), file, folder);
            });
        }

        public void ShowMessageBox(object? window, string message)
        {
            AppInstance.MainWindow.ShowMessageBox(window, message);
        }

        public void ShowNewDownloadDialog(Message message)
        {
            var url = message.Url;
            if (NewDownloadPromptTracker.IsPromptAlreadyOpen(url))
            {
                return;
            }
            AppInstance.MainWindow.RunOnUIThread(() =>
            {
                NewDownloadPromptTracker.PromptOpen(url);
                NewDownloadDialogHelper.CreateAndShowDialog(AppInstance.Core, this, this.CreateNewDownloadDialog(false), message,
                    () => NewDownloadPromptTracker.PromptClosed(url));
            });
        }

        public void ShowVideoDownloadDialog(string videoId, string name, long size, string? contentType)
        {
            RunOnUiThread(() =>
            {
                NewVideoDownloadDialogHelper.ShowVideoDownloadDialog(AppInstance.Core, this, this.CreateNewVideoDialog(),
                    videoId, name, size, contentType);
            });
        }

        public void UpdateItem(string id, string targetFileName, long size)
        {
            if (!AppDB.Instance.Downloads.UpdateNameAndSize(id, size, targetFileName))
            {
                Log.Debug("UpdateItem::failed");
            }
            RunOnUiThread(() =>
            {
                var download = AppInstance.MainWindow.FindInProgressItem(id);
                if (download == null) return;
                download.Name = targetFileName;
                download.Size = size;
                //this.SaveInProgressList();
            });
        }

        private void UpdateProgressOnUI(string id, int progress, double speed, long eta)
        {
            var downloadEntry = AppInstance.MainWindow.FindInProgressItem(id);
            if (downloadEntry != null)
            {
                downloadEntry.Progress = progress;
                downloadEntry.DownloadSpeed = Helpers.FormatSize(speed) + "/s";
                downloadEntry.ETA = Helpers.ToHMS(eta);
            }
        }

        public void UpdateProgress(string id, int progress, double speed, long eta)
        {
            if (!AppDB.Instance.Downloads.UpdateDownloadProgress(id, progress))
            {
                Log.Debug("UpdateProgress::failed");
            }
            AppInstance.MainWindow.RunOnUIThread(this.updateProgressAction, id, progress, speed, eta);
        }

        private void LoadDownloadList()
        {
            try
            {
                if (AppDB.Instance.Downloads.LoadDownloads(out var inProgressDownloads, out var finishedDownloads))
                {
                    AppInstance.MainWindow.InProgressDownloads = inProgressDownloads;
                    AppInstance.MainWindow.FinishedDownloads = finishedDownloads;
                    return;
                }
                else
                {
                    Log.Debug("Could not load download list");
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "LoadDownloadList");
            }
        }

        private void DisableButton(IButton button)
        {
            button.Enable = false;
        }

        private void EnableButton(IButton button)
        {
            button.Enable = true;
        }

        private void UpdateToolbarButtonState()
        {
            DisableButton(AppInstance.MainWindow.OpenFileButton);
            DisableButton(AppInstance.MainWindow.OpenFolderButton);
            DisableButton(AppInstance.MainWindow.PauseButton);
            DisableButton(AppInstance.MainWindow.ResumeButton);
            DisableButton(AppInstance.MainWindow.DeleteButton);

            if (AppInstance.MainWindow.IsInProgressViewSelected)
            {
                AppInstance.MainWindow.OpenFileButton.Visible = AppInstance.MainWindow.OpenFolderButton.Visible = false;
                AppInstance.MainWindow.PauseButton.Visible = AppInstance.MainWindow.ResumeButton.Visible = true;
                var selectedRows = AppInstance.MainWindow.SelectedInProgressRows;
                if (selectedRows.Count > 0)
                {
                    EnableButton(AppInstance.MainWindow.DeleteButton);
                }
                if (selectedRows.Count > 1)
                {
                    EnableButton(AppInstance.MainWindow.ResumeButton);
                    EnableButton(AppInstance.MainWindow.PauseButton);
                }
                else if (selectedRows.Count == 1)
                {
                    var ent = selectedRows[0];
                    var isActive = AppInstance.Core.IsDownloadActive(ent.DownloadEntry.Id);
                    if (isActive)
                    {
                        EnableButton(AppInstance.MainWindow.PauseButton);
                    }
                    else
                    {
                        EnableButton(AppInstance.MainWindow.ResumeButton);
                    }
                }
            }
            else
            {
                AppInstance.MainWindow.OpenFileButton.Visible = AppInstance.MainWindow.OpenFolderButton.Visible = true;
                AppInstance.MainWindow.PauseButton.Visible = AppInstance.MainWindow.ResumeButton.Visible = false;
                if (AppInstance.MainWindow.SelectedFinishedRows.Count > 0)
                {
                    EnableButton(AppInstance.MainWindow.DeleteButton);
                }

                if (AppInstance.MainWindow.SelectedFinishedRows.Count == 1)
                {
                    EnableButton(AppInstance.MainWindow.OpenFileButton);
                    EnableButton(AppInstance.MainWindow.OpenFolderButton);
                }
            }
        }

        private void DeleteDownloads()
        {
            UIActions.DeleteDownloads(AppInstance.MainWindow.IsInProgressViewSelected,
                AppInstance.MainWindow, AppInstance.Core, null);
            //inProgress =>
            //{
            //    if (inProgress)
            //    {
            //        SaveInProgressList();
            //    }
            //    else
            //    {
            //        SaveFinishedList();
            //    }
            //});
        }

        private void AttachedEventHandler()
        {
            AppInstance.MainWindow.NewDownloadClicked += (s, e) =>
            {
                AppInstance.MainWindow.RunOnUIThread(() =>
                {
                    NewDownloadDialogHelper.CreateAndShowDialog(AppInstance.Core, this, CreateNewDownloadDialog(true));
                });
            };

            AppInstance.MainWindow.YoutubeDLDownloadClicked += (s, e) =>
            {
                AppInstance.MainWindow.ShowYoutubeDLDialog(this, AppInstance.Core);
            };

            AppInstance.MainWindow.BatchDownloadClicked += (s, e) =>
            {
                AppInstance.MainWindow.ShowBatchDownloadWindow(AppInstance.Core, this);
            };

            AppInstance.MainWindow.SelectionChanged += (s, e) =>
            {
                UpdateToolbarButtonState();
            };

            AppInstance.MainWindow.CategoryChanged += (s, e) =>
            {
                UpdateToolbarButtonState();
            };

            AppInstance.MainWindow.NewButton.Clicked += (s, e) =>
            {
                AppInstance.MainWindow.OpenNewDownloadMenu();
            };

            AppInstance.MainWindow.DeleteButton.Clicked += (a, b) =>
            {
                DeleteDownloads();
            };

            AppInstance.MainWindow.DownloadListDoubleClicked += (a, b) => UIActions.OnDblClick(AppInstance.MainWindow, AppInstance.Core);

            AppInstance.MainWindow.OpenFolderButton.Clicked += (a, b) => UIActions.OpenSelectedFolder(AppInstance.MainWindow);

            AppInstance.MainWindow.OpenFileButton.Clicked += (a, b) =>
            {
                UIActions.OpenSelectedFile(AppInstance.MainWindow);
            };

            AppInstance.MainWindow.PauseButton.Clicked += (a, b) =>
            {
                if (AppInstance.MainWindow.IsInProgressViewSelected)
                {
                    UIActions.StopSelectedDownloads(AppInstance.MainWindow, AppInstance.Core);
                }
            };

            AppInstance.MainWindow.ResumeButton.Clicked += (a, b) =>
            {
                if (AppInstance.MainWindow.IsInProgressViewSelected)
                {
                    UIActions.ResumeDownloads(AppInstance.MainWindow, AppInstance.Core);
                }
            };

            AppInstance.MainWindow.SettingsClicked += (s, e) =>
            {
                AppInstance.MainWindow.ShowSettingsDialog(AppInstance.Core, 1);
                AppInstance.MainWindow.UpdateParallalismLabel();
            };

            AppInstance.MainWindow.BrowserMonitoringSettingsClicked += (s, e) =>
            {
                AppInstance.MainWindow.ShowBrowserMonitoringDialog(AppInstance.Core);
                AppInstance.MainWindow.UpdateParallalismLabel();
            };

            AppInstance.MainWindow.ClearAllFinishedClicked += (s, e) =>
            {
                AppInstance.MainWindow.DeleteAllFinishedDownloads();
                AppDB.Instance.Downloads.RemoveAllFinished();
                //SaveFinishedList();
            };

            AppInstance.MainWindow.ImportClicked += (s, e) =>
            {
                var file = AppInstance.MainWindow.OpenFileDialog(null, "zip", null);
                if (!string.IsNullOrEmpty(file) && File.Exists(file))
                {
                    Log.Debug("Exporting to: " + file);
                    AppInstance.Core.Import(file!);
                }
                LoadDownloadList();
            };

            AppInstance.MainWindow.ExportClicked += (s, e) =>
            {
                var file = AppInstance.MainWindow.SaveFileDialog("xdm-download-list.zip", "zip", "All files (*.*)|*.*");
                if (!string.IsNullOrEmpty(file))
                {
                    Log.Debug("Exporting to: " + file);
                    AppInstance.Core.Export(file!);
                }
            };

            AppInstance.MainWindow.HelpClicked += (s, e) =>
            {
                Helpers.OpenBrowser(AppInstance.Core.HelpPage);
            };

            AppInstance.MainWindow.UpdateClicked += (s, e) =>
            {
                if (AppInstance.Core.IsAppUpdateAvailable)
                {
                    Helpers.OpenBrowser(AppInstance.Core.UpdatePage);
                    return;
                }
                if (AppInstance.Core.IsComponentUpdateAvailable)
                {
                    if (AppInstance.MainWindow.Confirm(AppInstance.MainWindow, AppInstance.Core.ComponentUpdateText))
                    {
                        LaunchUpdater(UpdateMode.FFmpegUpdateOnly | UpdateMode.YoutubeDLUpdateOnly);
                        return;
                    }
                    else
                    {
                        return;
                    }
                }
                AppInstance.MainWindow.ShowMessageBox(AppInstance.MainWindow, TextResource.GetText("MSG_NO_UPDATE"));
            };

            AppInstance.MainWindow.BrowserMonitoringButtonClicked += (s, e) =>
            {
                if (Config.Instance.IsBrowserMonitoringEnabled)
                {
                    Config.Instance.IsBrowserMonitoringEnabled = false;
                }
                else
                {
                    Config.Instance.IsBrowserMonitoringEnabled = true;
                }
                Config.SaveConfig();
                AppInstance.Core.ApplyConfig();
                AppInstance.MainWindow.UpdateBrowserMonitorButton();
            };

            AppInstance.MainWindow.SupportPageClicked += (s, e) =>
            {
                Helpers.OpenBrowser("https://subhra74.github.io/xdm/redirect-support.html");
            };

            AppInstance.MainWindow.BugReportClicked += (s, e) =>
            {
                Helpers.OpenBrowser("https://subhra74.github.io/xdm/redirect-issue.html");
            };

            AppInstance.MainWindow.CheckForUpdateClicked += (s, e) =>
            {
                Helpers.OpenBrowser(AppInstance.Core.UpdatePage);
            };

            AppInstance.MainWindow.SchedulerClicked += (s, e) =>
            {
                ShowQueueWindow(AppInstance.MainWindow);
            };

            AppInstance.MainWindow.WindowCreated += (s, e) =>
            {
                this.WindowLoaded?.Invoke(this, EventArgs.Empty);
            };

            AttachContextMenuEvents();

            AppInstance.MainWindow.InProgressContextMenuOpening += (_, _) => InProgressContextMenuOpening();
            AppInstance.MainWindow.FinishedContextMenuOpening += (_, _) => FinishedContextMenuOpening();
        }

        public void ShowQueueWindow(object window)
        {
            QueueWindowManager.ShowWindow(window, AppInstance.MainWindow.CreateQueuesAndSchedulerWindow(this), AppInstance.Core);
        }

        private void LaunchUpdater(UpdateMode updateMode)
        {
            var updateDlg = AppInstance.MainWindow.CreateUpdateUIDialog(this);
            var updates = AppInstance.Core.Updates?.Where(u => u.IsExternal)?.ToList() ?? new List<UpdateInfo>(0);
            if (updates.Count == 0) return;
            var commonUpdateUi = new ComponentUpdaterUI(updateDlg, AppInstance.Core, updateMode);
            updateDlg.Load += (_, _) => commonUpdateUi.StartUpdate();
            updateDlg.Finished += (_, _) =>
            {
                RunOnUiThread(() =>
                {
                    AppInstance.MainWindow.ClearUpdateInformation();
                });
            };
            updateDlg.Show();
        }

        private void AttachContextMenuEvents()
        {
            try
            {
                AppInstance.MainWindow.MenuItemMap["pause"].Clicked += (_, _) => UIActions.StopSelectedDownloads(AppInstance.MainWindow, AppInstance.Core);
                AppInstance.MainWindow.MenuItemMap["resume"].Clicked += (_, _) => UIActions.ResumeDownloads(AppInstance.MainWindow, AppInstance.Core);
                AppInstance.MainWindow.MenuItemMap["delete"].Clicked += (_, _) => DeleteDownloads();
                AppInstance.MainWindow.MenuItemMap["saveAs"].Clicked += (_, _) => UIActions.SaveAs(AppInstance.MainWindow, AppInstance.Core);
                AppInstance.MainWindow.MenuItemMap["refresh"].Clicked += (_, _) => UIActions.RefreshLink(AppInstance.MainWindow, AppInstance.Core);
                AppInstance.MainWindow.MenuItemMap["moveToQueue"].Clicked += (_, _) => UIActions.MoveToQueue(AppInstance.MainWindow, this);
                AppInstance.MainWindow.MenuItemMap["showProgress"].Clicked += (_, _) => UIActions.ShowProgressWindow(AppInstance.MainWindow, AppInstance.Core);
                AppInstance.MainWindow.MenuItemMap["copyURL"].Clicked += (_, _) => UIActions.CopyURL1(AppInstance.MainWindow, AppInstance.Core);
                AppInstance.MainWindow.MenuItemMap["copyURL1"].Clicked += (_, _) => UIActions.CopyURL2(AppInstance.MainWindow, AppInstance.Core);
                AppInstance.MainWindow.MenuItemMap["properties"].Clicked += (_, _) => UIActions.ShowSeletectedItemProperties(AppInstance.MainWindow, AppInstance.Core);
                AppInstance.MainWindow.MenuItemMap["open"].Clicked += (_, _) => UIActions.OpenSelectedFile(AppInstance.MainWindow);
                AppInstance.MainWindow.MenuItemMap["openFolder"].Clicked += (_, _) => UIActions.OpenSelectedFolder(AppInstance.MainWindow);
                AppInstance.MainWindow.MenuItemMap["deleteDownloads"].Clicked += (_, _) => DeleteDownloads();
                AppInstance.MainWindow.MenuItemMap["copyFile"].Clicked += (_, _) => UIActions.CopyFile(AppInstance.MainWindow);
                AppInstance.MainWindow.MenuItemMap["properties1"].Clicked += (_, _) => UIActions.ShowSeletectedItemProperties(AppInstance.MainWindow, AppInstance.Core);
                AppInstance.MainWindow.MenuItemMap["downloadAgain"].Clicked += (_, _) => UIActions.RestartDownload(AppInstance.MainWindow, AppInstance.Core);
                AppInstance.MainWindow.MenuItemMap["restart"].Clicked += (_, _) => UIActions.RestartDownload(AppInstance.MainWindow, AppInstance.Core);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
        }

        private void InProgressContextMenuOpening()
        {
            foreach (var menu in AppInstance.MainWindow.MenuItems)
            {
                menu.Enabled = false;
            }
            AppInstance.MainWindow.MenuItemMap["delete"].Enabled = true;
            AppInstance.MainWindow.MenuItemMap["schedule"].Enabled = true;
            AppInstance.MainWindow.MenuItemMap["moveToQueue"].Enabled = true;
            var selectedRows = AppInstance.MainWindow.SelectedInProgressRows;
            if (selectedRows.Count > 1)
            {
                AppInstance.MainWindow.MenuItemMap["pause"].Enabled = true;
                AppInstance.MainWindow.MenuItemMap["resume"].Enabled = true;
                AppInstance.MainWindow.MenuItemMap["showProgress"].Enabled = true;
            }
            else if (selectedRows.Count == 1)
            {
                AppInstance.MainWindow.MenuItemMap["showProgress"].Enabled = true;
                AppInstance.MainWindow.MenuItemMap["copyURL"].Enabled = true;
                AppInstance.MainWindow.MenuItemMap["saveAs"].Enabled = true;
                AppInstance.MainWindow.MenuItemMap["refresh"].Enabled = true;
                AppInstance.MainWindow.MenuItemMap["properties"].Enabled = true;
                AppInstance.MainWindow.MenuItemMap["saveAs"].Enabled = true;
                AppInstance.MainWindow.MenuItemMap["saveAs"].Enabled = true;
                AppInstance.MainWindow.MenuItemMap["copyURL"].Enabled = true;

                var ent = selectedRows[0].DownloadEntry;//selectedRows[0].Cells[1].Value as InProgressDownloadEntry;
                if (ent == null) return;
                var isActive = AppInstance.Core.IsDownloadActive(ent.Id);
                Log.Debug("Selected item active: " + isActive);
                if (isActive)
                {
                    AppInstance.MainWindow.MenuItemMap["pause"].Enabled = true;
                }
                else
                {
                    AppInstance.MainWindow.MenuItemMap["resume"].Enabled = true;
                    AppInstance.MainWindow.MenuItemMap["restart"].Enabled = true;
                }
            }
        }

        private void FinishedContextMenuOpening()
        {
            foreach (var menu in AppInstance.MainWindow.MenuItems)
            {
                menu.Enabled = false;
            }

            AppInstance.MainWindow.MenuItemMap["deleteDownloads"].Enabled = true;

            var selectedRows = AppInstance.MainWindow.SelectedFinishedRows;
            if (selectedRows.Count == 1)
            {
                foreach (var menu in AppInstance.MainWindow.MenuItems)
                {
                    menu.Enabled = true;
                }
            }
        }

        public void InstallLatestFFmpeg()
        {
            LaunchUpdater(UpdateMode.FFmpegUpdateOnly);
        }

        public void InstallLatestYoutubeDL()
        {
            LaunchUpdater(UpdateMode.YoutubeDLUpdateOnly);
        }

        public void ShowDownloadSelectionWindow(FileNameFetchMode mode, IEnumerable<object> downloads)
        {
            RunOnUiThread(() =>
            {
                AppInstance.MainWindow.ShowDownloadSelectionWindow(AppInstance.Core, this, mode, downloads);
            });
        }

        public IClipboardMonitor GetClipboardMonitor()
        {
            return AppInstance.MainWindow.GetClipboardMonitor();
        }

        public void ShowFloatingVideoWidget()
        {
            AppInstance.MainWindow.ShowFloatingWidget();
        }
    }
}
