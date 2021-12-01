using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Windows.Forms;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;
using XDM.Core.Lib.Util;
using XDMApp;

namespace XDM.WinForm.UI
{
    internal interface IVirtualList<T>
    {
        public int IndexOf(T binder);

        public void UpdateDataGridView(int row, int col);
    }

    internal class DownloadDB
    {
        internal DownloadDB(DataGridView dgActive, DataGridView dgComplete)
        {
            InProgressItems = new(dgActive);
        }
        public FinishedDownloadsDB FinishedItems { get; } = new();
        public InProgressDownloadsDB InProgressItems { get; }

        internal class InProgressDownloadsDB : IVirtualList<InProgressDownloadEntryBinder>
        {
            private DataGridView dataGrid;
            private List<InProgressDownloadEntryBinder> inProgressItems = new(0);
            private List<InProgressDownloadEntryBinder> inProgressItemsView = new(0);
            private readonly InProgressDownloadComparer<InProgressDownloadEntryBinder> comparer = new();
            private string? searchKeyword;
            private SortParam sortParam = new SortParam { SortField = SortField.Modified, IsAscending = false };

            public InProgressDownloadEntryBinder this[int index] => inProgressItemsView[index];

            public int RowCount => inProgressItemsView.Count;

            internal InProgressDownloadsDB(DataGridView dataGrid)
            {
                this.dataGrid = dataGrid;
            }

            public void UpdateView()
            {
                lock (this)
                {
                    UpdateView(searchKeyword, this.sortParam);
                }
            }

            public void UpdateView(string? searchKeyword = null)
            {
                lock (this)
                {
                    UpdateView(searchKeyword, this.sortParam);
                }
            }

            public void UpdateView(string? searchKeyword, SortParam sortParam)
            {
                lock (this)
                {
                    this.searchKeyword = searchKeyword;
                    this.sortParam = sortParam;
                    inProgressItemsView.Clear();
                    inProgressItemsView.Capacity = inProgressItems.Count;
                    inProgressItemsView.AddRange(this.inProgressItems.Where(x => string.IsNullOrEmpty(searchKeyword) ||
                        x.Name.ToLowerInvariant().Contains(searchKeyword?.ToLowerInvariant())));
                    this.comparer.SortParam = sortParam;
                    inProgressItemsView.Sort(this.comparer);
                }
            }


            public int IndexOf(InProgressDownloadEntryBinder binder)
            {
                return inProgressItemsView.IndexOf(binder);
            }

            public void UpdateDataGridView(int row, int col)
            {
                if (row < 0) return;
                dataGrid.InvalidateCell(col, row);
            }

            public InProgressDownloadEntryBinder? FindDownload(string id)
            {
                lock (this)
                {
                    foreach (var item in inProgressItems)
                    {
                        if (id == item.DownloadEntry.Id)
                        {
                            return item;
                        }
                    }
                }
                return null;
            }

            public void Add(InProgressDownloadEntry download)
            {
                Add(new InProgressDownloadEntryBinder(download, this));
            }

            public void Add(InProgressDownloadEntryBinder binder)
            {
                lock (this)
                {
                    this.inProgressItems.Insert(0, binder);
                    UpdateView();
                }
            }

            public void Delete(InProgressDownloadEntryBinder item)
            {
                lock (this)
                {
                    this.inProgressItems.Remove(item);
                    UpdateView();
                }
            }

            public void Delete(InProgressDownloadEntry download)
            {
                lock (this)
                {
                    foreach (var item in this.inProgressItems)
                    {
                        if (item.DownloadEntry == download)
                        {
                            this.inProgressItems.Remove(item);
                            break;
                        }
                    }
                    UpdateView();
                }
            }

            public void Delete(IEnumerable<InProgressDownloadEntryBinder> items)
            {
                lock (this)
                {
                    foreach (var item in items)
                    {
                        this.inProgressItems.Remove(item);
                    }
                    UpdateView();
                }
            }

            public void Clear()
            {
                lock (this)
                {
                    this.inProgressItems.Clear();
                    UpdateView();
                }
            }

            public IEnumerable<InProgressDownloadEntry> AllItems
            {
                get
                {
                    lock (this)
                    {
                        foreach (var item in this.inProgressItems)
                        {
                            yield return item.DownloadEntry;
                        }
                    }
                }
            }

            public void Load(IEnumerable<InProgressDownloadEntry> list)
            {
                lock (this)
                {
                    this.inProgressItems = new List<InProgressDownloadEntryBinder>(
                        list.Select(x => new InProgressDownloadEntryBinder(x, this)).ToList());
                    UpdateView();
                }
            }
        }

        internal class FinishedDownloadsDB
        {
            private List<FinishedDownloadEntry> finishedDownloads = new(0);
            private List<FinishedDownloadEntry> finishedDownloadsView = new(0);
            private readonly DownloadComparer<FinishedDownloadEntry> comparer = new();
            private string? searchKeyword;
            private Category? category;
            private SortParam sortParam = new SortParam { SortField = SortField.Modified, IsAscending = false };

            public int RowCount => finishedDownloadsView.Count;

            public FinishedDownloadEntry this[int index] => finishedDownloadsView[index];

            public void UpdateView()
            {
                lock (this)
                {
                    UpdateView(searchKeyword, category, this.sortParam);
                }
            }

            public void UpdateView(string? searchKeyword = null, Category? category = null)
            {
                lock (this)
                {
                    UpdateView(searchKeyword, category, this.sortParam);
                }
            }

            public void UpdateView(string? searchKeyword, Category? category, SortParam sortParam)
            {
                lock (this)
                {
                    this.searchKeyword = searchKeyword;
                    this.category = category;
                    this.sortParam = sortParam;
                    finishedDownloadsView.Clear();
                    finishedDownloadsView.Capacity = finishedDownloads.Count;
                    finishedDownloadsView.AddRange(Helpers.FilterByCategoryOrKeyword(
                                            finishedDownloads, searchKeyword, category));
                    this.comparer.SortParam = sortParam;
                    finishedDownloadsView.Sort(this.comparer);
                }
            }

            public void Load(IEnumerable<FinishedDownloadEntry> list)
            {
                lock (this)
                {
                    finishedDownloads.AddRange(list);
                    UpdateView();
                }
            }

            public void Add(FinishedDownloadEntry finishedDownload)
            {
                lock (this)
                {
                    finishedDownloads.Add(finishedDownload);
                    UpdateView();
                }
            }

            public void Delete(int index)
            {
                lock (this)
                {
                    finishedDownloads.Remove(finishedDownloadsView[index]);
                    UpdateView();
                }
            }

            public void Delete(int[] indexes)
            {
                lock (this)
                {
                    foreach (var index in indexes)
                    {
                        finishedDownloads.Remove(finishedDownloadsView[index]);
                    }
                    UpdateView();
                }
            }

            public void Delete(FinishedDownloadEntry download)
            {
                lock (this)
                {
                    this.finishedDownloads.Remove(download);
                    UpdateView();
                }
            }

            public void Delete(IEnumerable<FinishedDownloadEntry> downloads)
            {
                lock (this)
                {
                    foreach (var item in downloads)
                    {
                        this.finishedDownloads.Remove(item);
                    }
                    UpdateView();
                }
            }

            public void Delete(IEnumerable<IFinishedDownloadRow> downloads)
            {
                lock (this)
                {
                    foreach (var item in downloads)
                    {
                        this.finishedDownloads.Remove(item.DownloadEntry);
                    }
                    UpdateView();
                }
            }

            public void Clear()
            {
                lock (this)
                {
                    finishedDownloads.Clear();
                    UpdateView();
                }
            }

            public IEnumerable<FinishedDownloadEntry> AllItems
            {
                get
                {
                    foreach (var item in finishedDownloads)
                    {
                        yield return item;
                    }
                }
            }
        }

        class DownloadComparer<T> : IComparer<T> where T : BaseDownloadEntry
        {
            public SortParam SortParam { get; set; }
            public int Compare(T? x, T? y)
            {
                if (x == null && y == null) return 0;
                if (x == null) return -1;
                if (y == null) return 1;
                var res = 0;
                switch (SortParam.SortField)
                {
                    case SortField.Name:
                        res = x.Name.CompareTo(y.Name);
                        break;
                    case SortField.Size:
                        res = x.Size.CompareTo(y.Size);
                        break;
                    case SortField.Modified:
                        res = x.DateAdded.CompareTo(y.DateAdded);
                        break;
                }
                return SortParam.IsAscending ? res : -res;
            }
        }

        class InProgressDownloadComparer<T> : IComparer<T> where T : InProgressDownloadEntryBinder
        {
            public SortParam SortParam { get; set; }
            public int Compare(T? x, T? y)
            {
                if (x == null && y == null) return 0;
                if (x == null) return -1;
                if (y == null) return 1;
                var res = 0;
                switch (SortParam.SortField)
                {
                    case SortField.Name:
                        res = x.Name.CompareTo(y.Name);
                        break;
                    case SortField.Size:
                        res = x.Size.CompareTo(y.Size);
                        break;
                    case SortField.Modified:
                        res = x.DateAdded.CompareTo(y.DateAdded);
                        break;
                }
                return SortParam.IsAscending ? res : -res;
            }
        }

    }

    internal class FinishedDownloadEntryBinder : IFinishedDownloadRow
    {
        public FinishedDownloadEntry DownloadEntry { get; }

        public string FileIconText => IconResource.GetFontIconForFileType(DownloadEntry.Name);

        public string Name => DownloadEntry.Name;

        public long Size => DownloadEntry.Size;

        public DateTime DateAdded => DownloadEntry.DateAdded;

        FinishedDownloadEntry IFinishedDownloadRow.DownloadEntry => DownloadEntry;

        public FinishedDownloadEntryBinder(FinishedDownloadEntry entry)
        {
            this.DownloadEntry = entry;
        }
    }

    internal class InProgressDownloadEntryBinder : IInProgressDownloadRow
    {
        public InProgressDownloadEntry DownloadEntry { get; set; }
        private IVirtualList<InProgressDownloadEntryBinder> downloadsDB;

        public InProgressDownloadEntryBinder(InProgressDownloadEntry downloadEntry, IVirtualList<InProgressDownloadEntryBinder> downloadsDB)
        {
            DownloadEntry = downloadEntry;
            this.downloadsDB = downloadsDB;
        }

        public string FileIconText
        {
            get => IconResource.GetFontIconForFileType(DownloadEntry.Name);
        }

        public string Name
        {
            get => DownloadEntry.Name;
            set
            {
                this.DownloadEntry.Name = value;
                var index = downloadsDB.IndexOf(this);
                downloadsDB.UpdateDataGridView(index, 1);
            }
        }

        public long Size
        {
            get => this.DownloadEntry.Size;
            set
            {
                this.DownloadEntry.Size = value;
                var index = downloadsDB.IndexOf(this);
                downloadsDB.UpdateDataGridView(index, 3);
            }
        }

        public DateTime DateAdded
        {
            get => this.DownloadEntry.DateAdded;
            set
            {
                this.DownloadEntry.DateAdded = value;
                var index = downloadsDB.IndexOf(this);
                downloadsDB.UpdateDataGridView(index, 2);
            }
        }

        public int Progress
        {
            get => this.DownloadEntry.Progress;
            set
            {
                this.DownloadEntry.Progress = value;
                var index = downloadsDB.IndexOf(this);
                downloadsDB.UpdateDataGridView(index, 4);
            }
        }

        public DownloadStatus Status
        {
            get => this.DownloadEntry.Status;
            set
            {
                this.DownloadEntry.Status = value;
                var index = downloadsDB.IndexOf(this);
                downloadsDB.UpdateDataGridView(index, 6);
            }
        }

        public string DownloadSpeed
        {
            get => DownloadEntry.DownloadSpeed ?? string.Empty;
            set
            {
                this.DownloadEntry.DownloadSpeed = value;
                var index = downloadsDB.IndexOf(this);
                downloadsDB.UpdateDataGridView(index, 6);
            }
        }

        public string ETA
        {
            get => DownloadEntry.ETA ?? string.Empty;
            set
            {
                this.DownloadEntry.ETA = value;
                var index = downloadsDB.IndexOf(this);
                downloadsDB.UpdateDataGridView(index, 6);
            }
        }
    }

    internal enum SortField
    {
        Name, Modified, Size
    }

    internal struct SortParam
    {
        public SortField SortField { get; set; }
        public bool IsAscending { get; set; }
    }
}
