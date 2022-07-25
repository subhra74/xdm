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
using XDM.Core.UI;
using XDM.Core.Util;

namespace XDM.Core
{
    public class AppController : IAppController
    {
        private IMainView peer;
        private IAppService app;
        private delegate void UpdateItemCallBack(string id, string targetFileName, long size);
        private Action<string, int, double, long> updateProgressAction;
        private long lastProgressUpdate = 0;
        public event EventHandler WindowLoaded;

        public AppController(IMainView peer, IAppService app)
        {
            this.peer = peer;
            this.app = app;
            this.updateProgressAction = new Action<string, int, double, long>(this.UpdateProgressOnUI);

            AttachedEventHandler();

            this.LoadDownloadList();

            UpdateToolbarButtonState();
        }

        public IAppService App { get => app; set => app = value; }

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
                //var downloadEntry = new InProgressDownloadEntry
                //{
                //    Name = targetFileName,
                //    DateAdded = date,
                //    DownloadType = type,
                //    Id = id,
                //    Progress = 0,
                //    Size = fileSize,
                //    Status = startType == DownloadStartType.Waiting ? DownloadStatus.Waiting : DownloadStatus.Stopped,
                //    TargetDir = "",
                //    PrimaryUrl = primaryUrl,
                //    Authentication = authentication,
                //    Proxy = proxyInfo,
                //    MaxSpeedLimitInKiB = maxSpeedLimit,
                //};

                this.peer.AddToTop(downloadEntry);
                this.peer.SwitchToInProgressView();
                this.peer.ClearInProgressViewSelection();

                //this.SaveInProgressList();
                UpdateToolbarButtonState();
            });
        }

        public bool Confirm(object? window, string text)
        {
            return peer.Confirm(window, text);
        }

        public IDownloadCompleteDialog CreateDownloadCompleteDialog()
        {
            return peer.CreateDownloadCompleteDialog(this.app);
        }

        public INewDownloadDialogSkeleton CreateNewDownloadDialog(bool empty)
        {
            return peer.CreateNewDownloadDialog(empty);
        }

        public INewVideoDownloadDialog CreateNewVideoDialog()
        {
            return peer.CreateNewVideoDialog();
        }

        public IProgressWindow CreateProgressWindow(string downloadId)
        {
            return peer.CreateProgressWindow(downloadId, this.app, this);
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
                CallbackActions.DownloadFailed(id, peer);
                //SaveInProgressList();
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
                    var download = peer.FindInProgressItem(id);
                    if (download == null) return;

                    peer.AddToTop(finishedEntry);
                    peer.Delete(download);

                    QueueManager.RemoveFinishedDownload(download.DownloadEntry.Id);

                    if (app.ActiveDownloadCount == 0 && peer.IsInProgressViewSelected)
                    {
                        Log.Debug("switching to finished listview");
                        peer.SwitchToFinishedView();
                    }
                });
            }

            //var download = peer.FindInProgressItem(id);
            //if (download == null) return;

            //peer.AddToTop(finishedEntry);
            //peer.Delete(download);

            //QueueManager.RemoveFinishedDownload(download.DownloadEntry.Id);

            //if (app.ActiveDownloadCount == 0 && peer.IsInProgressViewSelected)
            //{
            //    Log.Debug("switching to finished listview");
            //    peer.SwitchToFinishedView();
            //}

            //RunOnUiThread(() =>
            //{
            //    CallbackActions.DownloadFinished(id, finalFileSize, filePath, peer, app, () =>
            //    {
            //        UpdateToolbarButtonState();
            //        QueueWindowManager.RefreshView();
            //    });

            //    //this.SaveFinishedList();
            //    //this.SaveInProgressList();
            //    //UpdateToolbarButtonState();
            //    //QueueWindowManager.RefreshView();
            //});
        }

        public void DownloadStarted(string id)
        {
            RunOnUiThread(() =>
            {
                CallbackActions.DownloadStarted(id, peer);
                UpdateToolbarButtonState();
            });
        }

        public IEnumerable<InProgressDownloadEntry> GetAllInProgressDownloads()
        {
            //var downloads = new List<InProgressDownloadEntry>();
            //if (!AppDB.Instance.DownloadsDB.LoadDownloads(out downloads, out _, QueryMode.InProgress))
            //{
            //    Log.Debug("GetAllInProgressDownloads::failed");
            //}
            //return downloads;
            return peer.InProgressDownloads;
        }

        public InProgressDownloadEntry? GetInProgressDownloadEntry(string downloadId)
        {
            return peer.FindInProgressItem(downloadId)?.DownloadEntry;
        }

        public string? GetUrlFromClipboard()
        {
            var text = peer.GetUrlFromClipboard();
            if (Helpers.IsUriValid(text))
            {
                return text;
            }
            return null;
        }

        public AuthenticationInfo? PromtForCredentials(string message)
        {
            return peer.PromtForCredentials(message);
        }

        public void RenameFileOnUI(string id, string folder, string file)
        {
            if (!AppDB.Instance.Downloads.UpdateNameAndFolder(id, file, folder))
            {
                Log.Debug("RenameFileOnUI::failed");
            }
            RunOnUiThread(() =>
            {
                var downloadEntry = this.peer.FindInProgressItem(id);
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
            var download = peer.FindInProgressItem(downloadId);
            if (download == null) return;
            idDict[download.DownloadEntry.Id] = download.DownloadEntry;
            App.ResumeDownload(idDict);
        }

        public void RunOnUiThread(Action action)
        {
            peer.RunOnUIThread(action);
        }

        public void SetDownloadStatusWaiting(string id)
        {
            RunOnUiThread(() =>
            {
                var download = this.peer.FindInProgressItem(id);
                if (download == null) return;
                download.Status = DownloadStatus.Waiting;
                UpdateToolbarButtonState();
            });
        }

        public void ShowUpdateAvailableNotification()
        {
            RunOnUiThread(() =>
            {
                peer.ShowUpdateAvailableNotification();
            });
        }

        public void ShowDownloadCompleteDialog(string file, string folder)
        {
            RunOnUiThread(() =>
            {
                DownloadCompleteDialogHelper.ShowDialog(this.App, CreateDownloadCompleteDialog(), file, folder);
            });
        }

        public void ShowMessageBox(object? window, string message)
        {
            peer.ShowMessageBox(window, message);
        }

        public void ShowNewDownloadDialog(Message message)
        {
            var url = message.Url;
            if (NewDownloadPromptTracker.IsPromptAlreadyOpen(url))
            {
                return;
            }
            peer.RunOnUIThread(() =>
            {
                NewDownloadPromptTracker.PromptOpen(url);
                NewDownloadDialogHelper.CreateAndShowDialog(this.App, this, this.CreateNewDownloadDialog(false), message,
                    () => NewDownloadPromptTracker.PromptClosed(url));
            });
        }

        public void ShowVideoDownloadDialog(string videoId, string name, long size, string? contentType)
        {
            RunOnUiThread(() =>
            {
                NewVideoDownloadDialogHelper.ShowVideoDownloadDialog(this.App, this, this.CreateNewVideoDialog(),
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
                var download = peer.FindInProgressItem(id);
                if (download == null) return;
                download.Name = targetFileName;
                download.Size = size;
                //this.SaveInProgressList();
            });
        }

        private void UpdateProgressOnUI(string id, int progress, double speed, long eta)
        {
            var downloadEntry = peer.FindInProgressItem(id);
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
            peer.RunOnUIThread(this.updateProgressAction, id, progress, speed, eta);
        }

        private void LoadDownloadList()
        {
            try
            {
                if (AppDB.Instance.Downloads.LoadDownloads(out var inProgressDownloads, out var finishedDownloads))
                {
                    peer.InProgressDownloads = inProgressDownloads;
                    peer.FinishedDownloads = finishedDownloads;
                    return;
                }
                else
                {
                    Log.Debug("Could not load download list");
                }
                //peer.InProgressDownloads = TransactedIO.ReadInProgressList("inprogress-downloads.dat", Config.DataDir);
                //peer.FinishedDownloads = TransactedIO.ReadFinishedList("finished-downloads.dat", Config.DataDir);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "LoadDownloadList");
            }
        }

        //private void SaveInProgressList()
        //{
        //    lock (this)
        //    {
        //        TransactedIO.WriteInProgressList(peer.InProgressDownloads, "inprogress-downloads.dat", Config.DataDir);
        //    }
        //}

        //private void SaveFinishedList()
        //{
        //    lock (this)
        //    {
        //        TransactedIO.WriteFinishedList(peer.FinishedDownloads, "finished-downloads.dat", Config.DataDir);
        //    }
        //}

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
            DisableButton(peer.OpenFileButton);
            DisableButton(peer.OpenFolderButton);
            DisableButton(peer.PauseButton);
            DisableButton(peer.ResumeButton);
            DisableButton(peer.DeleteButton);

            if (peer.IsInProgressViewSelected)
            {
                peer.OpenFileButton.Visible = peer.OpenFolderButton.Visible = false;
                peer.PauseButton.Visible = peer.ResumeButton.Visible = true;
                var selectedRows = peer.SelectedInProgressRows;
                if (selectedRows.Count > 0)
                {
                    EnableButton(peer.DeleteButton);
                }
                if (selectedRows.Count > 1)
                {
                    EnableButton(peer.ResumeButton);
                    EnableButton(peer.PauseButton);
                }
                else if (selectedRows.Count == 1)
                {
                    var ent = selectedRows[0];
                    var isActive = App.IsDownloadActive(ent.DownloadEntry.Id);
                    if (isActive)
                    {
                        EnableButton(peer.PauseButton);
                    }
                    else
                    {
                        EnableButton(peer.ResumeButton);
                    }
                }
            }
            else
            {
                peer.OpenFileButton.Visible = peer.OpenFolderButton.Visible = true;
                peer.PauseButton.Visible = peer.ResumeButton.Visible = false;
                if (peer.SelectedFinishedRows.Count > 0)
                {
                    EnableButton(peer.DeleteButton);
                }

                if (peer.SelectedFinishedRows.Count == 1)
                {
                    EnableButton(peer.OpenFileButton);
                    EnableButton(peer.OpenFolderButton);
                }
            }
        }

        private void DeleteDownloads()
        {
            UIActions.DeleteDownloads(peer.IsInProgressViewSelected,
                peer, App, null);
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
            peer.NewDownloadClicked += (s, e) =>
            {
                peer.RunOnUIThread(() =>
                {
                    NewDownloadDialogHelper.CreateAndShowDialog(this.App, this, CreateNewDownloadDialog(true));
                });
            };

            peer.YoutubeDLDownloadClicked += (s, e) =>
            {
                peer.ShowYoutubeDLDialog(this, app);
            };

            peer.BatchDownloadClicked += (s, e) =>
            {
                peer.ShowBatchDownloadWindow(app, this);
            };

            peer.SelectionChanged += (s, e) =>
            {
                UpdateToolbarButtonState();
            };

            peer.CategoryChanged += (s, e) =>
            {
                UpdateToolbarButtonState();
            };

            peer.NewButton.Clicked += (s, e) =>
            {
                peer.OpenNewDownloadMenu();
            };

            peer.DeleteButton.Clicked += (a, b) =>
            {
                DeleteDownloads();
            };

            peer.DownloadListDoubleClicked += (a, b) => UIActions.OnDblClick(peer, App);

            peer.OpenFolderButton.Clicked += (a, b) => UIActions.OpenSelectedFolder(peer);

            peer.OpenFileButton.Clicked += (a, b) =>
            {
                UIActions.OpenSelectedFile(peer);
            };

            peer.PauseButton.Clicked += (a, b) =>
            {
                if (peer.IsInProgressViewSelected)
                {
                    UIActions.StopSelectedDownloads(peer, App);
                }
            };

            peer.ResumeButton.Clicked += (a, b) =>
            {
                if (peer.IsInProgressViewSelected)
                {
                    UIActions.ResumeDownloads(peer, App);
                }
            };

            peer.SettingsClicked += (s, e) =>
            {
                peer.ShowSettingsDialog(app, 1);
                peer.UpdateParallalismLabel();
            };

            peer.BrowserMonitoringSettingsClicked += (s, e) =>
            {
                peer.ShowBrowserMonitoringDialog(app);
                peer.UpdateParallalismLabel();
            };

            peer.ClearAllFinishedClicked += (s, e) =>
            {
                peer.DeleteAllFinishedDownloads();
                AppDB.Instance.Downloads.RemoveAllFinished();
                //SaveFinishedList();
            };

            peer.ImportClicked += (s, e) =>
            {
                var file = peer.OpenFileDialog(null, "zip", null);
                if (!string.IsNullOrEmpty(file) && File.Exists(file))
                {
                    Log.Debug("Exporting to: " + file);
                    app.Import(file!);
                }
                LoadDownloadList();
            };

            peer.ExportClicked += (s, e) =>
            {
                var file = peer.SaveFileDialog("xdm-download-list.zip", "zip", "All files (*.*)|*.*");
                if (!string.IsNullOrEmpty(file))
                {
                    Log.Debug("Exporting to: " + file);
                    app.Export(file!);
                }
            };

            peer.HelpClicked += (s, e) =>
            {
                Helpers.OpenBrowser(app.HelpPage);
            };

            peer.UpdateClicked += (s, e) =>
            {
                if (App.IsAppUpdateAvailable)
                {
                    Helpers.OpenBrowser(App.UpdatePage);
                    return;
                }
                if (App.IsComponentUpdateAvailable)
                {
                    if (peer.Confirm(peer, App.ComponentUpdateText))
                    {
                        LaunchUpdater(UpdateMode.FFmpegUpdateOnly | UpdateMode.YoutubeDLUpdateOnly);
                        return;
                    }
                    else
                    {
                        return;
                    }
                }
                peer.ShowMessageBox(peer, TextResource.GetText("MSG_NO_UPDATE"));
            };

            peer.BrowserMonitoringButtonClicked += (s, e) =>
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
                app.ApplyConfig();
                peer.UpdateBrowserMonitorButton();
            };

            peer.SupportPageClicked += (s, e) =>
            {
                Helpers.OpenBrowser("https://subhra74.github.io/xdm/redirect-support.html");
            };

            peer.BugReportClicked += (s, e) =>
            {
                Helpers.OpenBrowser("https://subhra74.github.io/xdm/redirect-issue.html");
            };

            peer.CheckForUpdateClicked += (s, e) =>
            {
                Helpers.OpenBrowser(app.UpdatePage);
            };

            peer.SchedulerClicked += (s, e) =>
            {
                ShowQueueWindow(peer);
            };

            peer.WindowCreated += (s, e) =>
            {
                this.WindowLoaded?.Invoke(this, EventArgs.Empty);
            };

            AttachContextMenuEvents();

            peer.InProgressContextMenuOpening += (_, _) => InProgressContextMenuOpening();
            peer.FinishedContextMenuOpening += (_, _) => FinishedContextMenuOpening();
        }

        public void ShowQueueWindow(object window)
        {
            QueueWindowManager.ShowWindow(window, peer.CreateQueuesAndSchedulerWindow(this), this.app);
        }

        private void LaunchUpdater(UpdateMode updateMode)
        {
            var updateDlg = peer.CreateUpdateUIDialog(this);
            var updates = App.Updates?.Where(u => u.IsExternal)?.ToList() ?? new List<UpdateInfo>(0);
            if (updates.Count == 0) return;
            var commonUpdateUi = new ComponentUpdaterUI(updateDlg, app, updateMode);
            updateDlg.Load += (_, _) => commonUpdateUi.StartUpdate();
            updateDlg.Finished += (_, _) =>
            {
                RunOnUiThread(() =>
                {
                    peer.ClearUpdateInformation();
                });
            };
            updateDlg.Show();
        }

        private void AttachContextMenuEvents()
        {
            try
            {
                peer.MenuItemMap["pause"].Clicked += (_, _) => UIActions.StopSelectedDownloads(peer, App);
                peer.MenuItemMap["resume"].Clicked += (_, _) => UIActions.ResumeDownloads(peer, App);
                peer.MenuItemMap["delete"].Clicked += (_, _) => DeleteDownloads();
                peer.MenuItemMap["saveAs"].Clicked += (_, _) => UIActions.SaveAs(peer, App);
                peer.MenuItemMap["refresh"].Clicked += (_, _) => UIActions.RefreshLink(peer, App);
                peer.MenuItemMap["moveToQueue"].Clicked += (_, _) => UIActions.MoveToQueue(peer, this);
                peer.MenuItemMap["showProgress"].Clicked += (_, _) => UIActions.ShowProgressWindow(peer, App);
                peer.MenuItemMap["copyURL"].Clicked += (_, _) => UIActions.CopyURL1(peer, App);
                peer.MenuItemMap["copyURL1"].Clicked += (_, _) => UIActions.CopyURL2(peer, App);
                peer.MenuItemMap["properties"].Clicked += (_, _) => UIActions.ShowSeletectedItemProperties(peer, App);
                peer.MenuItemMap["open"].Clicked += (_, _) => UIActions.OpenSelectedFile(peer);
                peer.MenuItemMap["openFolder"].Clicked += (_, _) => UIActions.OpenSelectedFolder(peer);
                peer.MenuItemMap["deleteDownloads"].Clicked += (_, _) => DeleteDownloads();
                peer.MenuItemMap["copyFile"].Clicked += (_, _) => UIActions.CopyFile(peer);
                peer.MenuItemMap["properties1"].Clicked += (_, _) => UIActions.ShowSeletectedItemProperties(peer, App);
                peer.MenuItemMap["downloadAgain"].Clicked += (_, _) => UIActions.RestartDownload(peer, App);
                peer.MenuItemMap["restart"].Clicked += (_, _) => UIActions.RestartDownload(peer, App);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
        }

        private void InProgressContextMenuOpening()
        {
            foreach (var menu in peer.MenuItems)
            {
                menu.Enabled = false;
            }
            peer.MenuItemMap["delete"].Enabled = true;
            peer.MenuItemMap["schedule"].Enabled = true;
            peer.MenuItemMap["moveToQueue"].Enabled = true;
            var selectedRows = peer.SelectedInProgressRows;
            if (selectedRows.Count > 1)
            {
                peer.MenuItemMap["pause"].Enabled = true;
                peer.MenuItemMap["resume"].Enabled = true;
                peer.MenuItemMap["showProgress"].Enabled = true;
            }
            else if (selectedRows.Count == 1)
            {
                peer.MenuItemMap["showProgress"].Enabled = true;
                peer.MenuItemMap["copyURL"].Enabled = true;
                peer.MenuItemMap["saveAs"].Enabled = true;
                peer.MenuItemMap["refresh"].Enabled = true;
                peer.MenuItemMap["properties"].Enabled = true;
                peer.MenuItemMap["saveAs"].Enabled = true;
                peer.MenuItemMap["saveAs"].Enabled = true;
                peer.MenuItemMap["copyURL"].Enabled = true;

                var ent = selectedRows[0].DownloadEntry;//selectedRows[0].Cells[1].Value as InProgressDownloadEntry;
                if (ent == null) return;
                var isActive = App.IsDownloadActive(ent.Id);
                Log.Debug("Selected item active: " + isActive);
                if (isActive)
                {
                    peer.MenuItemMap["pause"].Enabled = true;
                }
                else
                {
                    peer.MenuItemMap["resume"].Enabled = true;
                    peer.MenuItemMap["restart"].Enabled = true;
                }
            }
        }

        private void FinishedContextMenuOpening()
        {
            foreach (var menu in peer.MenuItems)
            {
                menu.Enabled = false;
            }

            peer.MenuItemMap["deleteDownloads"].Enabled = true;

            var selectedRows = peer.SelectedFinishedRows;
            if (selectedRows.Count == 1)
            {
                foreach (var menu in peer.MenuItems)
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
                peer.ShowDownloadSelectionWindow(this.App, this, mode, downloads);
            });
        }

        public IClipboardMonitor GetClipboardMonitor()
        {
            return peer.GetClipboardMonitor();
        }

        public void ShowFloatingVideoWidget()
        {
            peer.ShowFloatingWidget();
        }
    }
}
