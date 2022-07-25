using System;
using System.Collections.Generic;


namespace XDM.Core
{
    public interface IListUI
    {
        public RowItem AddItem(string file, string date, int progress, string size, string id, long realSize, DateTime dateAdded, string type);
        public RowItem AddItemToTop(string file, string date, int progress, string size, string id, long realSize, DateTime dateAdded, string type);
        public void UpdateItem(RowItem item, string name, string size, long realSize);
        public void UpdateProgress(RowItem item, int progress);
        public void DownloadFinished(RowItem item, long size = -1);
        public void DownloadFailed(RowItem item);
        public void DownloadCanelled(RowItem item);
        public List<InProgressDownloadEntry> GetListData();
        public Dictionary<string, RowItem> SetListData(List<InProgressDownloadEntry> list);
        public void ShowNewDownloadDialog(Message message, string providedFileName = null);
        public void DeleteDownload(List<RowItem> items);
        public bool ConfirmDelete(string message);
        public void ShowVideoDownloadDialog(string id, string name);
        public void RefereshListView();
    }



    public interface RowItem
    {

    }
}
