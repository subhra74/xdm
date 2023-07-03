using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace XDM.Core.UI
{
    public interface IBatchDownloadView
    {
        void SetStartLetterRange(string[] range);
        void SetEndLetterRange(string[] range);
        void ShowWindow();
        void DestroyWindow();

        bool IsLetterMode { get; set; }
        bool IsUsingLeadingZero { get; set; }
        string Url { get; set; }
        char? StartLetter { get; }
        char? EndLetter { get; }
        int StartNumber { get; }
        int EndNumber { get; }
        int LeadingZeroCount { get; }
        string BatchAddress1 { get; set; }
        string BatchAddress2 { get; set; }
        string BatchAddressN { get; set; }
        bool IsBatchMode { get; }
        string UrlListText { get; set; }


        event EventHandler? PatternChanged;
        event EventHandler? OkClicked;
    }
}
