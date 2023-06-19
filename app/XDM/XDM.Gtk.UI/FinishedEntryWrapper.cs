using Gtk;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using XDM.Core;
using XDM.Core.UI;
using XDM.Core;

namespace XDM.GtkUI
{
    internal class FinishedEntryWrapper : IFinishedDownloadRow
    {
        private FinishedDownloadItem entry;
        private TreeIter treeIter;
        private ITreeModel store;

        public FinishedEntryWrapper(FinishedDownloadItem entry, TreeIter treeIter, ITreeModel store)
        {
            this.entry = entry;
            this.treeIter = treeIter;
            this.store = store;
        }

        public string FileIconText => IconResource.GetSVGNameForFileType(DownloadEntry.Name);

        public string Name => entry.Name;

        public long Size => entry.Size;

        public DateTime DateAdded => entry.DateAdded;

        public FinishedDownloadItem DownloadEntry => entry;

        internal TreeIter TreeIter => treeIter;

        internal ITreeModel Store => store;
    }
}
