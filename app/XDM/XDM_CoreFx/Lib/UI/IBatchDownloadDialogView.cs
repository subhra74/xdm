using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace XDM.Core.Lib.UI
{
    public interface IBatchDownloadDialogView
    {
        void SetStartLetterRange(string[] range);
        void SetEndLetterRange(string[] range);
        void ShowWindow(object parent);

        bool IsLetterMode { get; set; }
        bool IsUsingLeadingZero { get; set; }
        string Url { get; set; }

        event EventHandler? PatternChanged;
    }
}
