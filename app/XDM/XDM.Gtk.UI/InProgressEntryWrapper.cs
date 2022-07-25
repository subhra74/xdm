using Gtk;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using XDM.Core;
using XDM.Core.UI;
using XDM.Core.Util;
using XDMApp;

namespace XDM.GtkUI
{
    internal class InProgressEntryWrapper : IInProgressDownloadRow
    {
        private TreeIter treeIter;
        private ITreeModel store;

        internal InProgressEntryWrapper(InProgressDownloadEntry downloadEntry,
            TreeIter treeIter,
            ITreeModel store)
        {
            this.DownloadEntry = downloadEntry;
            this.treeIter = treeIter;
            this.store = store;
        }

        public InProgressDownloadEntry DownloadEntry { get; }

        public string FileIconText => IconResource.GetSVGNameForFileType(DownloadEntry.Name);

        public string Name
        {
            get => DownloadEntry.Name;
            set
            {
                this.DownloadEntry.Name = value;
                store.SetValue(treeIter, 0, value);
            }
        }

        public long Size
        {
            get => DownloadEntry.Size;
            set
            {
                this.DownloadEntry.Size = value;
                store.SetValue(treeIter, 2, Helpers.FormatSize(value));
            }
        }

        public DateTime DateAdded
        {
            get => DownloadEntry.DateAdded;
            set
            {
                this.DownloadEntry.DateAdded = value;
                store.SetValue(treeIter, 1, value.ToShortDateString());
            }
        }

        public int Progress
        {
            get => DownloadEntry.Progress;
            set
            {
                this.DownloadEntry.Progress = value;
                store.SetValue(treeIter, 3, value);
            }
        }

        public DownloadStatus Status
        {
            get => this.DownloadEntry.Status;
            set
            {
                this.DownloadEntry.Status = value;
                store.SetValue(treeIter, 4, Helpers.GenerateStatusText(this.DownloadEntry));
            }
        }

        public string DownloadSpeed
        {
            get => DownloadEntry.DownloadSpeed ?? string.Empty;
            set
            {
                this.DownloadEntry.DownloadSpeed = value;
                store.SetValue(treeIter, 4, Helpers.GenerateStatusText(this.DownloadEntry));
            }
        }

        public string ETA
        {
            get => DownloadEntry.ETA ?? string.Empty;
            set
            {
                this.DownloadEntry.ETA = value;
                store.SetValue(treeIter, 4, Helpers.GenerateStatusText(this.DownloadEntry));
            }
        }

        internal TreeIter TreeIter => treeIter;

        public ITreeModel GetStore()
        {
            return this.store;
        }
    }
}
