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
        public event EventHandler WindowLoaded;

        public Application()
        {
            ApplicationContext.Initialized += AppInstance_Initialized;
            this.updateProgressAction = new Action<string, int, double, long>(this.UpdateProgressOnUI);
        }

        private void AppInstance_Initialized(object? sender, EventArgs e)
        {
            AppDB.Instance.Init(Path.Combine(Config.DataDir, "downloads.db"));
            AttachedEventHandler();
            LoadDownloadList();
            UpdateToolbarButtonState();
            AppUpdater.QueryNewVersion(); 
            WindowLoaded += (_, _) => ApplicationContext.ClipboardMonitor.Start();
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
            var downloadEntry = new InProgressDownloadItem
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
                ApplicationContext.MainWindow.AddToTop(downloadEntry);
                ApplicationContext.MainWindow.SwitchToInProgressView();
                ApplicationContext.MainWindow.ClearInProgressViewSelection();
                UpdateToolbarButtonState();
            });
        }

        public bool Confirm(object? window, string text)
        {
            return ApplicationContext.MainWindow.Confirm(window, text);
        }

        public IDownloadCompleteDialog CreateDownloadCompleteDialog()
        {
            return ApplicationContext.MainWindow.CreateDownloadCompleteDialog();
        }

        public INewDownloadDialog CreateNewDownloadDialog(bool empty)
        {
            return ApplicationContext.MainWindow.CreateNewDownloadDialog(empty);
        }

        public INewVideoDownloadDialog CreateNewVideoDialog()
        {
            return ApplicationContext.MainWindow.CreateNewVideoDialog();
        }

        public IProgressWindow CreateProgressWindow(string downloadId)
        {
            return ApplicationContext.MainWindow.CreateProgressWindow(downloadId);
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
                CallbackActions.DownloadFailed(id);
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
                var finishedEntry = new FinishedDownloadItem
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
                    var download = ApplicationContext.MainWindow.FindInProgressItem(id);
                    if (download == null) return;

                    ApplicationContext.MainWindow.AddToTop(finishedEntry);
                    ApplicationContext.MainWindow.Delete(download);

                    QueueManager.RemoveFinishedDownload(download.DownloadEntry.Id);

                    if (ApplicationContext.CoreService.ActiveDownloadCount == 0 && ApplicationContext.MainWindow.IsInProgressViewSelected)
                    {
                        Log.Debug("switching to finished listview");
                        ApplicationContext.MainWindow.SwitchToFinishedView();
                    }
                });
            }
        }

        public void DownloadStarted(string id)
        {
            RunOnUiThread(() =>
            {
                CallbackActions.DownloadStarted(id);
                UpdateToolbarButtonState();
            });
        }

        public IEnumerable<InProgressDownloadItem> GetAllInProgressDownloads()
        {
            return ApplicationContext.MainWindow.InProgressDownloads;
        }

        public InProgressDownloadItem? GetInProgressDownloadEntry(string downloadId)
        {
            return ApplicationContext.MainWindow.FindInProgressItem(downloadId)?.DownloadEntry;
        }

        public string? GetUrlFromClipboard()
        {
            var text = ApplicationContext.MainWindow.GetUrlFromClipboard();
            if (Helpers.IsUriValid(text))
            {
                return text;
            }
            return null;
        }

        public AuthenticationInfo? PromtForCredentials(string message)
        {
            return ApplicationContext.MainWindow.PromtForCredentials(message);
        }

        public void RenameFileOnUI(string id, string folder, string file)
        {
            if (!AppDB.Instance.Downloads.UpdateNameAndFolder(id, file, folder))
            {
                Log.Debug("RenameFileOnUI::failed");
            }
            RunOnUiThread(() =>
            {
                var downloadEntry = ApplicationContext.MainWindow.FindInProgressItem(id);
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
            var idDict = new Dictionary<string, DownloadItemBase>();
            var download = ApplicationContext.MainWindow.FindInProgressItem(downloadId);
            if (download == null) return;
            idDict[download.DownloadEntry.Id] = download.DownloadEntry;
            ApplicationContext.CoreService.ResumeDownload(idDict);
        }

        public void RunOnUiThread(Action action)
        {
            ApplicationContext.MainWindow.RunOnUIThread(action);
        }

        public void SetDownloadStatusWaiting(string id)
        {
            RunOnUiThread(() =>
            {
                var download = ApplicationContext.MainWindow.FindInProgressItem(id);
                if (download == null) return;
                download.Status = DownloadStatus.Waiting;
                UpdateToolbarButtonState();
            });
        }

        public void ShowUpdateAvailableNotification()
        {
            RunOnUiThread(() =>
            {
                ApplicationContext.MainWindow.ShowUpdateAvailableNotification();
            });
        }

        public void ShowDownloadCompleteDialog(string file, string folder)
        {
            RunOnUiThread(() =>
            {
                DownloadCompleteUIController.ShowDialog(CreateDownloadCompleteDialog(), file, folder);
            });
        }

        public void ShowMessageBox(object? window, string message)
        {
            ApplicationContext.MainWindow.ShowMessageBox(window, message);
        }

        public void ShowNewDownloadDialog(Message message)
        {
            var url = message.Url;
            if (NewDownloadPromptTracker.IsPromptAlreadyOpen(url))
            {
                return;
            }
            ApplicationContext.MainWindow.RunOnUIThread(() =>
            {
                NewDownloadPromptTracker.PromptOpen(url);
                NewDownloadDialogUIController.CreateAndShowDialog(this.CreateNewDownloadDialog(false), message,
                    () => NewDownloadPromptTracker.PromptClosed(url));
            });
        }

        public void ShowVideoDownloadDialog(string videoId, string name, long size, string? contentType)
        {
            RunOnUiThread(() =>
            {
                NewVideoDownloadDialogUIController.ShowVideoDownloadDialog(this.CreateNewVideoDialog(),
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
                var download = ApplicationContext.MainWindow.FindInProgressItem(id);
                if (download == null) return;
                download.Name = targetFileName;
                download.Size = size;
                //this.SaveInProgressList();
            });
        }

        private void UpdateProgressOnUI(string id, int progress, double speed, long eta)
        {
            var downloadEntry = ApplicationContext.MainWindow.FindInProgressItem(id);
            if (downloadEntry != null)
            {
                downloadEntry.Progress = progress;
                downloadEntry.DownloadSpeed = FormattingHelper.FormatSize(speed) + "/s";
                downloadEntry.ETA = FormattingHelper.ToHMS(eta);
            }
        }

        public void UpdateProgress(string id, int progress, double speed, long eta)
        {
            if (!AppDB.Instance.Downloads.UpdateDownloadProgress(id, progress))
            {
                Log.Debug("UpdateProgress::failed");
            }
            ApplicationContext.MainWindow.RunOnUIThread(this.updateProgressAction, id, progress, speed, eta);
        }

        private void LoadDownloadList()
        {
            try
            {
                if (AppDB.Instance.Downloads.LoadDownloads(out var inProgressDownloads, out var finishedDownloads))
                {
                    ApplicationContext.MainWindow.InProgressDownloads = inProgressDownloads;
                    ApplicationContext.MainWindow.FinishedDownloads = finishedDownloads;
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
            DisableButton(ApplicationContext.MainWindow.OpenFileButton);
            DisableButton(ApplicationContext.MainWindow.OpenFolderButton);
            DisableButton(ApplicationContext.MainWindow.PauseButton);
            DisableButton(ApplicationContext.MainWindow.ResumeButton);
            DisableButton(ApplicationContext.MainWindow.DeleteButton);

            if (ApplicationContext.MainWindow.IsInProgressViewSelected)
            {
                ApplicationContext.MainWindow.OpenFileButton.Visible = ApplicationContext.MainWindow.OpenFolderButton.Visible = false;
                ApplicationContext.MainWindow.PauseButton.Visible = ApplicationContext.MainWindow.ResumeButton.Visible = true;
                var selectedRows = ApplicationContext.MainWindow.SelectedInProgressRows;
                if (selectedRows.Count > 0)
                {
                    EnableButton(ApplicationContext.MainWindow.DeleteButton);
                }
                if (selectedRows.Count > 1)
                {
                    EnableButton(ApplicationContext.MainWindow.ResumeButton);
                    EnableButton(ApplicationContext.MainWindow.PauseButton);
                }
                else if (selectedRows.Count == 1)
                {
                    var ent = selectedRows[0];
                    var isActive = ApplicationContext.CoreService.IsDownloadActive(ent.DownloadEntry.Id);
                    if (isActive)
                    {
                        EnableButton(ApplicationContext.MainWindow.PauseButton);
                    }
                    else
                    {
                        EnableButton(ApplicationContext.MainWindow.ResumeButton);
                    }
                }
            }
            else
            {
                ApplicationContext.MainWindow.OpenFileButton.Visible = ApplicationContext.MainWindow.OpenFolderButton.Visible = true;
                ApplicationContext.MainWindow.PauseButton.Visible = ApplicationContext.MainWindow.ResumeButton.Visible = false;
                if (ApplicationContext.MainWindow.SelectedFinishedRows.Count > 0)
                {
                    EnableButton(ApplicationContext.MainWindow.DeleteButton);
                }

                if (ApplicationContext.MainWindow.SelectedFinishedRows.Count == 1)
                {
                    EnableButton(ApplicationContext.MainWindow.OpenFileButton);
                    EnableButton(ApplicationContext.MainWindow.OpenFolderButton);
                }
            }
        }

        private void DeleteDownloads()
        {
            UIActions.DeleteDownloads(ApplicationContext.MainWindow.IsInProgressViewSelected, null);
        }

        private void AttachedEventHandler()
        {
            ApplicationContext.MainWindow.NewDownloadClicked += (s, e) =>
            {
                ApplicationContext.MainWindow.RunOnUIThread(() =>
                {
                    NewDownloadDialogUIController.CreateAndShowDialog(CreateNewDownloadDialog(true));
                });
            };

            ApplicationContext.MainWindow.YoutubeDLDownloadClicked += (s, e) =>
            {
                ApplicationContext.MainWindow.ShowYoutubeDLDialog();
            };

            ApplicationContext.MainWindow.BatchDownloadClicked += (s, e) =>
            {
                ApplicationContext.MainWindow.ShowBatchDownloadWindow();
            };

            ApplicationContext.MainWindow.SelectionChanged += (s, e) =>
            {
                UpdateToolbarButtonState();
            };

            ApplicationContext.MainWindow.CategoryChanged += (s, e) =>
            {
                UpdateToolbarButtonState();
            };

            ApplicationContext.MainWindow.NewButton.Clicked += (s, e) =>
            {
                ApplicationContext.MainWindow.OpenNewDownloadMenu();
            };

            ApplicationContext.MainWindow.DeleteButton.Clicked += (a, b) =>
            {
                DeleteDownloads();
            };

            ApplicationContext.MainWindow.DownloadListDoubleClicked += (a, b) => UIActions.OnDblClick();

            ApplicationContext.MainWindow.OpenFolderButton.Clicked += (a, b) => UIActions.OpenSelectedFolder();

            ApplicationContext.MainWindow.OpenFileButton.Clicked += (a, b) =>
            {
                UIActions.OpenSelectedFile();
            };

            ApplicationContext.MainWindow.PauseButton.Clicked += (a, b) =>
            {
                if (ApplicationContext.MainWindow.IsInProgressViewSelected)
                {
                    UIActions.StopSelectedDownloads();
                }
            };

            ApplicationContext.MainWindow.ResumeButton.Clicked += (a, b) =>
            {
                if (ApplicationContext.MainWindow.IsInProgressViewSelected)
                {
                    UIActions.ResumeDownloads();
                }
            };

            ApplicationContext.MainWindow.SettingsClicked += (s, e) =>
            {
                ApplicationContext.MainWindow.ShowSettingsDialog(1);
                ApplicationContext.MainWindow.UpdateParallalismLabel();
            };

            ApplicationContext.MainWindow.BrowserMonitoringSettingsClicked += (s, e) =>
            {
                ApplicationContext.MainWindow.ShowBrowserMonitoringDialog();
                ApplicationContext.MainWindow.UpdateParallalismLabel();
            };

            ApplicationContext.MainWindow.ClearAllFinishedClicked += (s, e) =>
            {
                ApplicationContext.MainWindow.DeleteAllFinishedDownloads();
                AppDB.Instance.Downloads.RemoveAllFinished();
                //SaveFinishedList();
            };

            ApplicationContext.MainWindow.ImportClicked += (s, e) =>
            {
                var file = ApplicationContext.MainWindow.OpenFileDialog(null, "zip", null);
                if (!string.IsNullOrEmpty(file) && File.Exists(file))
                {
                    Log.Debug("Exporting to: " + file);
                    ApplicationContext.CoreService.Import(file!);
                }
                LoadDownloadList();
            };

            ApplicationContext.MainWindow.ExportClicked += (s, e) =>
            {
                var file = ApplicationContext.MainWindow.SaveFileDialog("xdm-download-list.zip", "zip", "All files (*.*)|*.*");
                if (!string.IsNullOrEmpty(file))
                {
                    Log.Debug("Exporting to: " + file);
                    ApplicationContext.CoreService.Export(file!);
                }
            };

            ApplicationContext.MainWindow.HelpClicked += (s, e) =>
            {
                PlatformHelper.OpenBrowser(Links.SupportUrl);
            };

            ApplicationContext.MainWindow.UpdateClicked += (s, e) =>
            {
                if (AppUpdater.IsAppUpdateAvailable)
                {
                    PlatformHelper.OpenBrowser(AppUpdater.UpdatePage);
                    return;
                }
                if (AppUpdater.IsComponentUpdateAvailable)
                {
                    if (ApplicationContext.MainWindow.Confirm(ApplicationContext.MainWindow, AppUpdater.ComponentUpdateText))
                    {
                        LaunchUpdater(UpdateMode.FFmpegUpdateOnly | UpdateMode.YoutubeDLUpdateOnly);
                        return;
                    }
                    else
                    {
                        return;
                    }
                }
                ApplicationContext.MainWindow.ShowMessageBox(ApplicationContext.MainWindow, TextResource.GetText("MSG_NO_UPDATE"));
            };

            ApplicationContext.MainWindow.BrowserMonitoringButtonClicked += (s, e) =>
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
                ApplicationContext.BroadcastConfigChange();
                ApplicationContext.MainWindow.UpdateBrowserMonitorButton();
            };

            ApplicationContext.MainWindow.SupportPageClicked += (s, e) =>
            {
                PlatformHelper.OpenBrowser(Links.SupportUrl);
            };

            ApplicationContext.MainWindow.BugReportClicked += (s, e) =>
            {
                PlatformHelper.OpenBrowser(Links.IssueUrl);
            };

            ApplicationContext.MainWindow.CheckForUpdateClicked += (s, e) =>
            {
                PlatformHelper.OpenBrowser(AppUpdater.UpdatePage);
            };

            ApplicationContext.MainWindow.SchedulerClicked += (s, e) =>
            {
                ShowQueueWindow(ApplicationContext.MainWindow);
            };

            ApplicationContext.MainWindow.WindowCreated += (s, e) =>
            {
                this.WindowLoaded?.Invoke(this, EventArgs.Empty);
            };

            AttachContextMenuEvents();

            ApplicationContext.MainWindow.InProgressContextMenuOpening += (_, _) => InProgressContextMenuOpening();
            ApplicationContext.MainWindow.FinishedContextMenuOpening += (_, _) => FinishedContextMenuOpening();
        }

        public void ShowQueueWindow(object window)
        {
            QueueWindowManager.ShowWindow(window, ApplicationContext.MainWindow.CreateQueuesAndSchedulerWindow());
        }

        private void LaunchUpdater(UpdateMode updateMode)
        {
            var updateDlg = ApplicationContext.MainWindow.CreateUpdateUIDialog();
            var updates = AppUpdater.Updates?.Where(u => u.IsExternal)?.ToList() ?? new List<UpdateInfo>(0);
            if (updates.Count == 0) return;
            var commonUpdateUi = new ComponentUpdaterUIController(updateDlg, updateMode);
            updateDlg.Load += (_, _) => commonUpdateUi.StartUpdate();
            updateDlg.Finished += (_, _) =>
            {
                RunOnUiThread(() =>
                {
                    ApplicationContext.MainWindow.ClearUpdateInformation();
                });
            };
            updateDlg.Show();
        }

        private void AttachContextMenuEvents()
        {
            try
            {
                ApplicationContext.MainWindow.MenuItemMap["pause"].Clicked += (_, _) => UIActions.StopSelectedDownloads();
                ApplicationContext.MainWindow.MenuItemMap["resume"].Clicked += (_, _) => UIActions.ResumeDownloads();
                ApplicationContext.MainWindow.MenuItemMap["delete"].Clicked += (_, _) => DeleteDownloads();
                ApplicationContext.MainWindow.MenuItemMap["saveAs"].Clicked += (_, _) => UIActions.SaveAs();
                ApplicationContext.MainWindow.MenuItemMap["refresh"].Clicked += (_, _) => UIActions.RefreshLink();
                ApplicationContext.MainWindow.MenuItemMap["moveToQueue"].Clicked += (_, _) => UIActions.MoveToQueue();
                ApplicationContext.MainWindow.MenuItemMap["showProgress"].Clicked += (_, _) => UIActions.ShowProgressWindow();
                ApplicationContext.MainWindow.MenuItemMap["copyURL"].Clicked += (_, _) => UIActions.CopyURL1();
                ApplicationContext.MainWindow.MenuItemMap["copyURL1"].Clicked += (_, _) => UIActions.CopyURL2();
                ApplicationContext.MainWindow.MenuItemMap["properties"].Clicked += (_, _) => UIActions.ShowSeletectedItemProperties();
                ApplicationContext.MainWindow.MenuItemMap["open"].Clicked += (_, _) => UIActions.OpenSelectedFile();
                ApplicationContext.MainWindow.MenuItemMap["openFolder"].Clicked += (_, _) => UIActions.OpenSelectedFolder();
                ApplicationContext.MainWindow.MenuItemMap["deleteDownloads"].Clicked += (_, _) => DeleteDownloads();
                ApplicationContext.MainWindow.MenuItemMap["copyFile"].Clicked += (_, _) => UIActions.CopyFile();
                ApplicationContext.MainWindow.MenuItemMap["properties1"].Clicked += (_, _) => UIActions.ShowSeletectedItemProperties();
                ApplicationContext.MainWindow.MenuItemMap["downloadAgain"].Clicked += (_, _) => UIActions.RestartDownload();
                ApplicationContext.MainWindow.MenuItemMap["restart"].Clicked += (_, _) => UIActions.RestartDownload();
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
        }

        private void InProgressContextMenuOpening()
        {
            foreach (var menu in ApplicationContext.MainWindow.MenuItems)
            {
                menu.Enabled = false;
            }
            ApplicationContext.MainWindow.MenuItemMap["delete"].Enabled = true;
            ApplicationContext.MainWindow.MenuItemMap["schedule"].Enabled = true;
            ApplicationContext.MainWindow.MenuItemMap["moveToQueue"].Enabled = true;
            var selectedRows = ApplicationContext.MainWindow.SelectedInProgressRows;
            if (selectedRows.Count > 1)
            {
                ApplicationContext.MainWindow.MenuItemMap["pause"].Enabled = true;
                ApplicationContext.MainWindow.MenuItemMap["resume"].Enabled = true;
                ApplicationContext.MainWindow.MenuItemMap["showProgress"].Enabled = true;
            }
            else if (selectedRows.Count == 1)
            {
                ApplicationContext.MainWindow.MenuItemMap["showProgress"].Enabled = true;
                ApplicationContext.MainWindow.MenuItemMap["copyURL"].Enabled = true;
                ApplicationContext.MainWindow.MenuItemMap["saveAs"].Enabled = true;
                ApplicationContext.MainWindow.MenuItemMap["refresh"].Enabled = true;
                ApplicationContext.MainWindow.MenuItemMap["properties"].Enabled = true;
                ApplicationContext.MainWindow.MenuItemMap["saveAs"].Enabled = true;
                ApplicationContext.MainWindow.MenuItemMap["saveAs"].Enabled = true;
                ApplicationContext.MainWindow.MenuItemMap["copyURL"].Enabled = true;

                var ent = selectedRows[0].DownloadEntry;//selectedRows[0].Cells[1].Value as InProgressDownloadEntry;
                if (ent == null) return;
                var isActive = ApplicationContext.CoreService.IsDownloadActive(ent.Id);
                Log.Debug("Selected item active: " + isActive);
                if (isActive)
                {
                    ApplicationContext.MainWindow.MenuItemMap["pause"].Enabled = true;
                }
                else
                {
                    ApplicationContext.MainWindow.MenuItemMap["resume"].Enabled = true;
                    ApplicationContext.MainWindow.MenuItemMap["restart"].Enabled = true;
                }
            }
        }

        private void FinishedContextMenuOpening()
        {
            foreach (var menu in ApplicationContext.MainWindow.MenuItems)
            {
                menu.Enabled = false;
            }

            ApplicationContext.MainWindow.MenuItemMap["deleteDownloads"].Enabled = true;

            var selectedRows = ApplicationContext.MainWindow.SelectedFinishedRows;
            if (selectedRows.Count == 1)
            {
                foreach (var menu in ApplicationContext.MainWindow.MenuItems)
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

        public void ShowDownloadSelectionWindow(FileNameFetchMode mode, IEnumerable<IRequestData> downloads)
        {
            RunOnUiThread(() =>
            {
                ApplicationContext.MainWindow.ShowDownloadSelectionWindow(mode, downloads);
            });
        }

        public IPlatformClipboardMonitor GetPlatformClipboardMonitor()
        {
            return ApplicationContext.MainWindow.GetClipboardMonitor();
        }

        public void ShowFloatingVideoWidget()
        {
            ApplicationContext.MainWindow.ShowFloatingWidget();
        }
    }
}
