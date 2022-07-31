using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using Translations;
using XDM.Core;
using XDM.Core.Downloader;
using XDM.Core.Downloader.Adaptive.Dash;
using XDM.Core.Downloader.Adaptive.Hls;
using XDM.Core.Downloader.Progressive.DualHttp;
using XDM.Core.Downloader.Progressive.SingleHttp;
using XDM.Core.UI;
using XDM.Core.Util;

namespace XDM.Core.UI
{
    public class DownloadSelectionUIController
    {
        private IDownloadSelectionView view;
        private FileNameFetchMode mode;

        public DownloadSelectionUIController(IDownloadSelectionView view,
            FileNameFetchMode mode, IEnumerable<IRequestData> downloads)
        {
            this.view = view;
            this.mode = mode;

            string? folder = null;
            if (Config.Instance.FolderSelectionMode == FolderSelectionMode.Manual)
            {
                folder = Helpers.GetManualDownloadFolder();
            }
            view.DownloadLocation = folder ?? Config.Instance.DefaultDownloadFolder;
            view.SetData(mode, downloads, PopuplateEntryWrapper);

            view.BrowseClicked += (_, _) =>
            {
                var folder = view.SelectFolder();
                if (!string.IsNullOrEmpty(folder))
                {
                    view.DownloadLocation = folder;
                    Config.Instance.UserSelectedDownloadFolder = folder;
                    Helpers.UpdateRecentFolderList(folder);
                }
            };
            view.DownloadClicked += View_DownloadClicked; ;
            view.DownloadLaterClicked += View_DownloadLaterClicked;
            view.QueueSchedulerClicked += (s, e) =>
            {
                ApplicationContext.Application.ShowQueueWindow(view);
            };
        }

        public void Run()
        {
            this.view.ShowWindow();
        }

        private void View_DownloadLaterClicked(object? sender, DownloadLaterEventArgs e)
        {
            DownloadSelectedItems(false, e.QueueId);
        }

        private void View_DownloadClicked(object? sender, EventArgs e)
        {
            DownloadSelectedItems(true, null);
        }

        private bool PopuplateEntryWrapper(IRequestData obj, IDownloadEntryWrapper entry)
        {
            if (obj is SingleSourceHTTPDownloadInfo shi)
            {
                entry.EntryType = "Http";
                entry.Name = shi.File ?? FileHelper.GetFileName(new Uri(shi.Uri));
            }
            else if (obj is DualSourceHTTPDownloadInfo dhi)
            {
                entry.EntryType = "Dash";
                entry.Name = dhi.File ?? FileHelper.GetFileName(new Uri(dhi.Uri1));
            }
            else if (obj is MultiSourceHLSDownloadInfo mhi)
            {
                entry.EntryType = "Hls";
                entry.Name = mhi.File ?? FileHelper.GetFileName(new Uri(mhi.VideoUri));
            }
            else if (obj is MultiSourceDASHDownloadInfo mdi)
            {
                entry.EntryType = "MpegDash";
                entry.Name = mdi.File ?? FileHelper.GetFileName(new Uri(mdi.Url));
            }
            else
            {
                return false;
            }
            entry.DownloadEntry = obj;
            return true;
        }

        private void AddDownload(IDownloadEntryWrapper wrapper, bool startImmediately, string? queueId)
        {
            ApplicationContext.CoreService.StartDownload(
                        wrapper.DownloadEntry,
                        wrapper.Name,
                        mode,
                        view.DownloadLocation,
                        startImmediately,
                        view.Authentication,
                        view.Proxy ?? Config.Instance.Proxy,
                        queueId,
                        false
                    );
        }

        private void DownloadSelectedItems(bool startImmediately, string? queueId)
        {
            if (string.IsNullOrEmpty(view.DownloadLocation))
            {
                ApplicationContext.Application.ShowMessageBox(view, TextResource.GetText("MSG_CAT_FOLDER_MISSING"));
                return;
            }
            if (view.SelectedRowCount == 0)
            {
                ApplicationContext.Application.ShowMessageBox(view, TextResource.GetText("BAT_SELECT_ITEMS"));
                return;
            }

            foreach (var item in view.SelectedItems)
            {
                if (item.IsSelected)
                {
                    AddDownload(item, startImmediately, queueId);
                }
            }
            view.CloseWindow();
        }
    }
}
