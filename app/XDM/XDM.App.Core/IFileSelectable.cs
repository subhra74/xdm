using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

using XDM.Core.UI;

namespace XDM.Core
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
