using System;
using System.ComponentModel;
using XDM.Core.UI;
using XDM.Core;
using XDM.Core.UI;

namespace XDM.Wpf.UI
{
    internal class FinishedDownloadEntryWrapper : INotifyPropertyChanged, IFinishedDownloadRow
    {
        private FinishedDownloadEntry entry;

        public event PropertyChangedEventHandler PropertyChanged;

        public FinishedDownloadEntryWrapper(FinishedDownloadEntry entry)
        {
            this.entry = entry;
        }

        public string Name
        {
            get { return entry.Name; }
            set
            {
                entry.Name = value;
                OnPropertyChanged("Name");
            }
        }

        public long Size
        {
            get { return entry.Size; }
            set
            {
                entry.Size = value;
                OnPropertyChanged("Size");
            }
        }

        public DateTime DateAdded
        {
            get { return entry.DateAdded; }
            set
            {
                entry.DateAdded = value;
                OnPropertyChanged("DateAdded");
            }
        }

        /// <summary>
        /// This needs to be called after updating download speed or stopping the download
        /// </summary>
        public void UpdateStatusText()
        {
            OnPropertyChanged("Status");
        }

        public FinishedDownloadEntry DownloadEntry => this.entry;

        public string FileIconText => IconMap.GetVectorNameForFileType(entry.Name);

        private void OnPropertyChanged(string propName)
        {
            PropertyChanged(this, new PropertyChangedEventArgs(propName));
        }
    }
}
