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
using XDM.Core.Lib.Util;
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
        public bool IsLetterMode { get => CmbType.SelectedIndex == 0; set => CmbType.SelectedIndex = value ? 0 : 1; }
        public bool IsUsingLeadingZero { get => IsChecked(ChkLeadingZero); set => ChkLeadingZero.IsChecked = value; }
        public string Url { get => TxtAddress.Text; set => TxtAddress.Text = value; }
        public char? StartLetter => CmbLetterFrom.SelectedIndex < 0 ? null : CmbLetterFrom.SelectedItem.ToString()[0];
        public char? EndLetter => CmbLetterTo.SelectedIndex < 0 ? null : CmbLetterTo.SelectedItem.ToString()[0];
        public int StartNumber => Helpers.ParseIntSafe(TxtNumberFrom.Text);
        public int EndNumber => Helpers.ParseIntSafe(TxtNumberTo.Text);
        public int LeadingZeroCount => Helpers.ParseIntSafe(TxtLeadingZero.Text);
        public string BatchAddress1 { get => TxtFile1.Text; set => TxtFile1.Text = value; }
        public string BatchAddress2 { get => TxtFile2.Text; set => TxtFile2.Text = value; }
        public string BatchAddressN { get => TxtFileN.Text; set => TxtFileN.Text = value; }
        public bool IsBatchMode => this.TabControl.SelectedIndex == 0;

        public event EventHandler? PatternChanged;
        public event EventHandler? OkClicked;

        public void SetStartLetterRange(string[] range)
        {
            this.CmbLetterFrom.ItemsSource = range;
        }

        public void SetEndLetterRange(string[] range)
        {
            this.CmbLetterTo.ItemsSource = range;
        }

        public void ShowWindow()
        {
            this.Show();
        }

        public void DestroyWindow()
        {
            Close();
        }

        public BatchDownloadWindow()
        {
            InitializeComponent();
        }

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

        private bool IsChecked(CheckBox checkBox) => checkBox.IsChecked ?? false;

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

        private void OnBatchPatternChange()
        {
            PatternChanged?.Invoke(this, EventArgs.Empty);
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
            OkClicked?.Invoke(this, EventArgs.Empty);
            //if (this.TabControl.SelectedIndex == 0)
            //{
            //    var links = GenerateBatchLink()?.Select(x => (object)new SingleSourceHTTPDownloadInfo { Uri = x.ToString() });
            //    if (links == null || !links.Any())
            //    {
            //        AppUI.ShowMessageBox(this, TextResource.GetText("BAT_SELECT_ITEMS"));
            //        return;
            //    }
            //    var window = new DownloadSelectionWindow(App, AppUI, Core.Lib.Downloader.FileNameFetchMode.FileNameAndExtension, links);
            //    this.Close();
            //    window.Show();
            //}
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            Close();
        }
    }
}
