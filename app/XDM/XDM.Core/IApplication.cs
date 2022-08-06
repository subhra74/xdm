using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

using XDM.Core.UI;
using XDM.Core;
using XDM.Core.Downloader;

namespace XDM.Core
{
    public interface IApplication
    {
        /// <summary>
        /// 
        /// </summary>
        /// <param name="id"></param>
        /// <param name="progress"></param>
        void UpdateProgress(string id, int progress, double speed, long eta);
        /// <summary>
        /// 
        /// </summary>
        /// <param name="id"></param>
        /// <param name="finalFileSize"></param>
        /// <param name="filePath"></param>
        void DownloadFinished(string id, long finalFileSize, string filePath);
        /// <summary>
        /// 
        /// </summary>
        /// <param name="id"></param>
        void DownloadFailed(string id);
        /// <summary>
        /// 
        /// </summary>
        /// <param name="id"></param>
        void DownloadCanelled(string id);

        /// <summary>
        /// 
        /// </summary>
        /// <param name="id"></param>
        /// <param name="targetFileName"></param>
        /// <param name="date"></param>
        /// <param name="fileSize"></param>
        /// <param name="type"></param>
        /// <param name="fileNameFetchMode"></param>
        /// <param name="primaryUrl"></param>
        /// <param name="startType"></param>
        /// <param name="authentication"></param>
        /// <param name="proxyInfo"></param>
        public void AddItemToTop(string id, string targetFileName,
            string? targetDir, DateTime date,
            long fileSize, string type, FileNameFetchMode fileNameFetchMode,
            string primaryUrl, DownloadStartType startType,
            AuthenticationInfo? authentication, ProxyInfo? proxyInfo);

        /// <summary>
        /// 
        /// </summary>
        /// <param name="id"></param>
        /// <param name="targetFileName"></param>
        /// <param name="fileSize"></param>
        /// <param name="realSize"></param>
        void UpdateItem(string id, string targetFileName, long size);

        INewDownloadDialog CreateNewDownloadDialog(bool empty);

        INewVideoDownloadDialog CreateNewVideoDialog();

        /// <summary>
        /// 
        /// </summary>
        /// <param name="message"></param>
        public void ShowNewDownloadDialog(Message message);

        /// <summary>
        /// 
        /// </summary>
        /// <param name="videoId"></param>
        /// <param name="name"></param>
        /// <param name="size"></param>
        public void ShowVideoDownloadDialog(string videoId, string name, long size, string? contentType);
        /// <summary>
        /// 
        /// </summary>
        /// <param name=""></param>
        /// <returns></returns>
        public bool Confirm(object? window, string text);
        /// <summary>
        /// 
        /// </summary>
        /// <param name="id"></param>
        public void DownloadStarted(string id);

        /// <summary>
        /// 
        /// </summary>
        /// <param name="downloadId"></param>
        /// <returns></returns>
        public IProgressWindow CreateProgressWindow(string downloadId);

        /// <summary>
        /// 
        /// </summary>
        /// <param name="wnd"></param>
        /// <param name="message"></param>
        public void ShowMessageBox(object? window, string message);

        /// <summary>
        /// 
        /// </summary>
        /// <param name="file"></param>
        /// <param name="folder"></param>
        /// <returns></returns>
        public void ShowDownloadCompleteDialog(string file, string folder);

        /// <summary>
        /// 
        /// </summary>
        /// <returns></returns>
        public IDownloadCompleteDialog CreateDownloadCompleteDialog();

        /// <summary>
        /// 
        /// </summary>
        /// <returns></returns>
        string? GetUrlFromClipboard();

        /// <summary>
        /// 
        /// </summary>
        /// <param name="downloadId"></param>
        public void ResumeDownload(string downloadId);

        /// <summary>
        /// Update toolbar buttons (pause/resume)
        /// </summary>
       // public void UpdateUIButtons();

        /// <summary>
        /// 
        /// </summary>
        /// <param name="downloadId"></param>
        /// <returns></returns>
        public InProgressDownloadItem? GetInProgressDownloadEntry(string downloadId);

        /// <summary>
        /// Execute the action on ui thread
        /// </summary>
        /// <param name="action"></param>
        public void RunOnUiThread(Action action);

        /// <summary>
        /// Warning: Must be called from UI thead only
        /// </summary>
        /// <param name="id"></param>
        public void SetDownloadStatusWaiting(string id);

        /// <summary>
        /// Warning: Must be called from UI thead only
        /// </summary>
        /// <returns></returns>
        public IEnumerable<InProgressDownloadItem> GetAllInProgressDownloads();

        /// <summary>
        /// 
        /// </summary>
        /// <returns></returns>
        //public IEnumerable<InProgresDownloadEntry> GetInprogressList();

        /// <summary>
        /// 
        /// </summary>
        /// <returns></returns>
        //public IEnumerable<FinishedDownloadEntry> GetFinishedList();
        /// <summary>
        /// 
        /// </summary>
        /// <param name="id"></param>
        /// <param name="folder"></param>
        /// <param name="file"></param>
        void RenameFileOnUI(string id, string folder, string file);

        public AuthenticationInfo? PromtForCredentials(string message);
        //public void LoadDownloadsDB();
        public void ShowUpdateAvailableNotification();

        public void InstallLatestFFmpeg();

        public void InstallLatestYoutubeDL();

        // public void MoveToQueue(string[] selectedIds, bool prompt = false, Action? callback = null);

        void ShowQueueWindow(object window);

        void ShowDownloadSelectionWindow(FileNameFetchMode mode, IEnumerable<IRequestData> downloads);

        IPlatformClipboardMonitor GetPlatformClipboardMonitor();

        event EventHandler WindowLoaded;
    }

    public enum DownloadStartType
    {
        Waiting, Stopped, Scheduled
    }

    public enum UpdateAction
    {
        LaunchBrowser, DownloadExternalApps
    }




    //public class InProgressDownloadListToBeSavedEventArgs
    //{
    //    public InProgresDownloadEntry Data { get; }
    //}

    //public class DownloadListToBeSavedEventArgs<T>
    //{
    //    public DownloadListToBeSavedEventArgs(T data)
    //    {
    //        this.Data = data;
    //    }
    //    public T Data { get; }
    //}
}
