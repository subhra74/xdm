using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using TraceLog;
using Translations;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Downloader.Progressive.SingleHttp;
using XDM.Core.Lib.UI;
using XDM.Wpf.UI.Dialogs.DownloadSelection;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.BatchDownload
{
    /// <summary>
    /// Interaction logic for BatchDownloadWindow.xaml
    /// </summary>
    public partial class BatchDownloadWindow : Window, IBatchDownloadDialogView
    {
        public int BatchSize { get; private set; } = 0;
        public IAppUI AppUI { get; set; }
        public IApp App { get; set; }
        public bool IsLetterMode { get => CmbType.SelectedIndex == 0; set => CmbType.SelectedIndex = value ? 0 : 1; }
        public bool IsUsingLeadingZero { get => IsChecked(ChkLeadingZero); set => ChkLeadingZero.IsChecked = value; }
        public string Url { get => TxtAddress.Text; set => TxtAddress.Text = value; }

        public void SetStartLetterRange(string[] range)
        {
            this.CmbLetterFrom.ItemsSource = range;
        }

        public void SetEndLetterRange(string[] range)
        {
            this.CmbLetterTo.ItemsSource = range;
        }

        public void ShowWindow(object parent)
        {
            throw new NotImplementedException();
        }

        public BatchDownloadWindow(IApp app, IAppUI appUI)
        {
            InitializeComponent();
            this.AppUI = appUI;
            this.App = app;
            var arr = new string[] { "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z" };
            this.CmbLetterFrom.ItemsSource = arr;
            this.CmbLetterTo.ItemsSource = arr;
            CmbType.SelectedIndex = 0;
        }

        public event EventHandler? PatternChanged;

        private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
        {

        }

        private void TxtPreviewTextInput(object sender, TextCompositionEventArgs e)
        {
            e.Handled = !Int32.TryParse(e.Text, out _);
        }

        private void TxtPasting(object sender, DataObjectPastingEventArgs e)
        {
            if (e.DataObject.GetDataPresent(typeof(string)))
            {
                string text = (string)e.DataObject.GetData(typeof(string));
                if (!Int32.TryParse(text, out _))
                {
                    e.CancelCommand();
                }
            }
            else
            {
                e.CancelCommand();
            }
        }

        private int ParseIntSafe(string text) => Int32.TryParse(text, out int n) ? n : 0;

        private bool IsChecked(CheckBox checkBox) => checkBox.IsChecked ?? false;

        private IEnumerable<Uri> GenerateBatchLink(string url)
        {
            if (CmbType.SelectedIndex == 0)
            {
                if (CmbLetterTo.SelectedIndex < 0 || CmbLetterFrom.SelectedIndex < 0) throw new ArgumentException();
                var startChar = CmbLetterFrom.SelectedItem.ToString()[0];
                var endChar = CmbLetterTo.SelectedItem.ToString()[0];

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
                var startNum = ParseIntSafe(TxtNumberFrom.Text);
                var endNum = ParseIntSafe(TxtNumberTo.Text);

                if (startNum >= endNum)
                {
                    throw new ArgumentException();
                }

                for (var i = startNum; i <= endNum; i++)
                {
                    var s = url.Replace("*",
                        IsChecked(ChkLeadingZero) ? i.ToString($"D{ParseIntSafe(TxtLeadingZero.Text)}") :
                        i.ToString());
                    yield return new Uri(s);
                }
            }
        }

        public IEnumerable<Uri> GenerateBatchLink()
        {
            if (!TxtAddress.Text.Contains('*')) return Enumerable.Empty<Uri>();
            try
            {
                return GenerateBatchLink(TxtAddress.Text);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error generating batch links");
                return Enumerable.Empty<Uri>();
            }
        }

        private void OnBatchPatternChange()
        {
            try
            {
                TxtFile1.Text = TxtFile2.Text = TxtFileN.Text = string.Empty;
                var c = 0;
                var last = string.Empty;
                BatchSize = 0;
                foreach (var url in GenerateBatchLink())
                {
                    if (c == 0)
                    {
                        TxtFile1.Text = url.ToString();
                    }
                    else if (c == 1)
                    {
                        TxtFile2.Text = url.ToString();
                    }
                    last = url.ToString();
                    c++;
                    BatchSize++;
                }
                if (c > 1)
                {
                    TxtFileN.Text = last;
                }
            }
            catch (UriFormatException)
            {
                AppUI?.ShowMessageBox(this, TextResource.GetText("MSG_INVALID_URL"));
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error");
            }
        }

        private void TxtLostFocus(object sender, RoutedEventArgs e)
        {
            OnBatchPatternChange();
        }

        private void CmbType_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            CmbLetterFrom.Visibility = CmbLetterTo.Visibility = CmbType.SelectedIndex == 0 ? Visibility.Visible : Visibility.Collapsed;
            TxtNumberFrom.Visibility = TxtNumberTo.Visibility = CmbType.SelectedIndex == 1 ? Visibility.Visible : Visibility.Collapsed;

            OnBatchPatternChange();
        }

        private void CmbLetterTo_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            OnBatchPatternChange();
        }

        private void CmbLetterFrom_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            OnBatchPatternChange();
        }

        private void ChkLeadingZero_Checked(object sender, RoutedEventArgs e)
        {
            OnBatchPatternChange();
        }

        private void ChkLeadingZero_Unchecked(object sender, RoutedEventArgs e)
        {
            OnBatchPatternChange();
        }

        private void TxtAddress_TextChanged(object sender, TextChangedEventArgs e)
        {
            OnBatchPatternChange();
        }

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);

            NativeMethods.DisableMinMaxButton(this);

#if NET45_OR_GREATER
            if (XDM.Wpf.UI.App.Skin == Skin.Dark)
            {
                var helper = new WindowInteropHelper(this);
                helper.EnsureHandle();
                DarkModeHelper.UseImmersiveDarkMode(helper.Handle, true);
            }
#endif
        }

        private void BtnOK_Click(object sender, RoutedEventArgs e)
        {
            if (this.TabControl.SelectedIndex == 0)
            {
                var links = GenerateBatchLink()?.Select(x => (object)new SingleSourceHTTPDownloadInfo { Uri = x.ToString() });
                if (links == null || !links.Any())
                {
                    AppUI.ShowMessageBox(this, TextResource.GetText("BAT_SELECT_ITEMS"));
                    return;
                }
                var window = new DownloadSelectionWindow(App, AppUI, Core.Lib.Downloader.FileNameFetchMode.FileNameAndExtension, links);
                this.Close();
                window.Show();
            }
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            Close();
        }
    }
}
