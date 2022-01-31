using System;
using System.ComponentModel;
using XDM.Common.UI;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;
using XDM.Core.Lib.Util;

namespace XDM.Wpf.UI
{
    internal class InProgressDownloadEntryWrapper : INotifyPropertyChanged, IInProgressDownloadRow
    {
        private readonly InProgressDownloadEntry entry;

        public event PropertyChangedEventHandler PropertyChanged;

        public InProgressDownloadEntryWrapper(InProgressDownloadEntry entry)
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

        public int Progress
        {
            get { return entry.Progress; }
            set
            {
                entry.Progress = value;
                OnPropertyChanged("Progress");
                OnPropertyChanged("Status");
            }
        }

        public string StatusText => Helpers.GenerateStatusText(this.entry);

        public InProgressDownloadEntry DownloadEntry => this.entry;

        public string FileIconText => IconMap.GetVectorNameForFileType(entry.Name);

        public DownloadStatus Status
        {
            get => entry.Status;
            set
            {
                entry.Status = value;
                OnPropertyChanged("Status");
            }
        }

        public string DownloadSpeed
        {
            get => entry.DownloadSpeed;
            set
            {
                entry.DownloadSpeed = value;
                OnPropertyChanged("Status");
            }
        }

        public string ETA
        {
            get => entry.ETA;
            set
            {
                entry.ETA = value;
                OnPropertyChanged("Status");
            }
        }

        /// <summary>
        /// This needs to be called after updating download speed or stopping the download
        /// </summary>
        public void UpdateStatusText()
        {
            OnPropertyChanged("Status");
        }

        private void OnPropertyChanged(string propName)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propName));
        }
    }
}
