using System;
using System.ComponentModel;
using XDM.Core.UI;
using XDM.Core;
using XDM.Core.UI;
using XDM.Core.Util;

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
                OnPropertyChanged("StatusText");
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
                OnPropertyChanged("StatusText");
            }
        }

        public string DownloadSpeed
        {
            get => entry.DownloadSpeed;
            set
            {
                entry.DownloadSpeed = value;
                OnPropertyChanged("Status");
                OnPropertyChanged("StatusText");
            }
        }

        public string ETA
        {
            get => entry.ETA;
            set
            {
                entry.ETA = value;
                OnPropertyChanged("Status");
                OnPropertyChanged("StatusText");
            }
        }

        /// <summary>
        /// This needs to be called after updating download speed or stopping the download
        /// </summary>
        public void UpdateStatusText()
        {
            OnPropertyChanged("Status");
            OnPropertyChanged("StatusText");
        }

        private void OnPropertyChanged(string propName)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propName));
        }
    }
}
