using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace XDM.Core.UI
{
    public interface IFileSelectable
    {
        string SelectedFileName { get; set; }
        int SeletedFolderIndex { get; set; }
        void SetFolderValues(string[] values);
        event EventHandler<FileBrowsedEventArgs> FileBrowsedEvent;
        event EventHandler<FileBrowsedEventArgs> DropdownSelectionChangedEvent;
    }
}
