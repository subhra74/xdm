using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using TraceLog;
using Translations;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Downloader.Progressive.SingleHttp;
using XDM.Core.Lib.UI;

namespace XDM.Common.UI
{
    public class BatchDownloadDialogViewController
    {
        private IBatchDownloadDialogView view;
        public IAppUI AppUI { get; set; }
        public IApp App { get; set; }
        public int BatchSize { get; private set; } = 0;

        public BatchDownloadDialogViewController(IBatchDownloadDialogView view, IApp app, IAppUI appUI)
        {
            this.view = view;
            this.AppUI = appUI;
            this.App = app;

            var arr = new string[] { "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z" };
            this.view.SetStartLetterRange(arr);
            this.view.SetEndLetterRange(arr);
            this.view.IsLetterMode = true;

            this.view.PatternChanged += View_PatternChanged;
            this.view.OkClicked += View_OkClicked;
        }

        private void View_OkClicked(object? sender, EventArgs e)
        {
            OnOKClicked();
        }

        private void View_PatternChanged(object? sender, EventArgs e)
        {
            OnBatchPatternChange();
        }

        public void Run()
        {
            this.view.ShowWindow();
        }

        private void OnOKClicked()
        {
            if (this.view.IsBatchMode)
            {
                var links = GenerateBatchLink()?.Select(x => (object)new SingleSourceHTTPDownloadInfo { Uri = x.ToString() });
                if (links == null || !links.Any())
                {
                    AppUI.ShowMessageBox(this.view, TextResource.GetText("BAT_SELECT_ITEMS"));
                    return;
                }
                this.view.DestroyWindow();
                //var window = new DownloadSelectionWindow(App, AppUI, Core.Lib.Downloader.FileNameFetchMode.FileNameAndExtension, links);
                //this.Close();
                //window.Show();
            }
        }

        private void OnBatchPatternChange()
        {
            try
            {
                view.BatchAddress1 = view.BatchAddress2 = view.BatchAddressN = string.Empty;
                var c = 0;
                var last = string.Empty;
                BatchSize = 0;
                foreach (var url in GenerateBatchLink())
                {
                    if (c == 0)
                    {
                        view.BatchAddress1 = url.ToString();
                    }
                    else if (c == 1)
                    {
                        view.BatchAddress2 = url.ToString();
                    }
                    last = url.ToString();
                    c++;
                    BatchSize++;
                }
                if (c > 1)
                {
                    view.BatchAddressN = last;
                }
            }
            catch (UriFormatException)
            {
                AppUI?.ShowMessageBox(this.view, TextResource.GetText("MSG_INVALID_URL"));
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error");
            }
        }

        public IEnumerable<Uri> GenerateBatchLink()
        {
            if (!this.view.Url.Contains('*')) return Enumerable.Empty<Uri>();
            try
            {
                return GenerateBatchLink(this.view.Url);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error generating batch links");
                return Enumerable.Empty<Uri>();
            }
        }

        private IEnumerable<Uri> GenerateBatchLink(string url)
        {
            if (this.view.IsLetterMode)
            {
                if (!(this.view.StartLetter.HasValue && this.view.EndLetter.HasValue)) throw new ArgumentException();
                var startChar = this.view.StartLetter.Value;
                var endChar = this.view.EndLetter.Value;

                if (startChar >= endChar)
                {
                    throw new ArgumentException();
                }

                for (var i = startChar; i <= endChar; i++)
                {
                    yield return new Uri(url.Replace('*', i));
                }
            }
            else
            {
                var startNum = this.view.StartNumber;// ParseIntSafe(TxtNumberFrom.Text);
                var endNum = this.view.EndNumber; //ParseIntSafe(TxtNumberTo.Text);

                if (startNum >= endNum)
                {
                    throw new ArgumentException();
                }

                for (var i = startNum; i <= endNum; i++)
                {
                    var s = url.Replace("*",
                        this.view.IsUsingLeadingZero ? i.ToString($"D{this.view.LeadingZeroCount}") :
                        i.ToString());
                    yield return new Uri(s);
                }
            }
        }
    }
}
