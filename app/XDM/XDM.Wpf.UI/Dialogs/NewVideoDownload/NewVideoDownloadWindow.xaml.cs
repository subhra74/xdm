using Microsoft.Win32;
using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
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
using XDM.Core;
using XDM.Core.UI;
using XDM.Wpf.UI.Common.Helpers;
using XDM.Wpf.UI.Dialogs.AdvancedDownloadOption;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.NewVideoDownload
{
    /// <summary>
    /// Interaction logic for NewVideoDownloadWindow.xaml
    /// </summary>
    public partial class NewVideoDownloadWindow : Window, INewVideoDownloadDialog
    {
        private int previousIndex = 0;
        public AuthenticationInfo? Authentication { get => authentication; set => authentication = value; }
        public ProxyInfo? Proxy { get => proxy; set => proxy = value; }
        public int SpeedLimit { get => speedLimit; set => speedLimit = value; }
        public bool EnableSpeedLimit { get => enableSpeedLimit; set => enableSpeedLimit = value; }

        private AuthenticationInfo? authentication;
        private ProxyInfo? proxy = Config.Instance.Proxy;
        private int speedLimit = Config.Instance.DefaltDownloadSpeed;
        private bool enableSpeedLimit = Config.Instance.EnableSpeedLimit;
        private ObservableCollection<string> dropdownItems = new();

        public NewVideoDownloadWindow()
        {
            InitializeComponent();
            CmbLocation.ItemsSource = dropdownItems;
        }

        public event EventHandler DownloadClicked;
        public event EventHandler<DownloadLaterEventArgs> DownloadLaterClicked;
        public event EventHandler CancelClicked, DestroyEvent, QueueSchedulerClicked, Mp3CheckChanged;
        public event EventHandler<FileBrowsedEventArgs> DropdownSelectionChangedEvent;
        public event EventHandler<FileBrowsedEventArgs> FileBrowsedEvent;

        public string SelectedFileName { get => TxtFile.Text; set => TxtFile.Text = value; }

        public string FileSize { get => TxtFileSize.Text; set => TxtFileSize.Text = value; }

        public int SeletedFolderIndex
        {
            get => CmbLocation.SelectedIndex;
            set
            {
                CmbLocation.SelectedIndex = value;
                previousIndex = value;
            }
        }

        public bool ShowMp3Checkbox
        {
            get => ChkMp3.Visibility == Visibility.Visible;
            set => ChkMp3.Visibility = value ? Visibility.Visible : Visibility.Collapsed;
        }

        public bool IsMp3CheckboxChecked { get => ChkMp3.IsChecked ?? false; set => ChkMp3.IsChecked = value; }

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

        private void btnAdvanced_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new AdvancedDownloadOptionDialog
            {
                Authentication = Authentication,
                Proxy = Proxy,
                EnableSpeedLimit = EnableSpeedLimit,
                SpeedLimit = SpeedLimit,
                Owner = this
            };
            var ret = dlg.ShowDialog(this);

            if (ret.HasValue && ret.Value)
            {
                Authentication = dlg.Authentication;
                Proxy = dlg.Proxy;
                EnableSpeedLimit = dlg.EnableSpeedLimit;
                SpeedLimit = dlg.SpeedLimit;
            }
        }

        private void btnDownloadLater_Click(object sender, RoutedEventArgs e)
        {
            DownloadLaterMenuHelper.PopulateMenuAndAttachEvents(DownloadLaterClicked, btnDownloadLater, this);
        }

        private void btnDownload_Click(object sender, RoutedEventArgs e)
        {
            DownloadClicked?.Invoke(sender, e);
        }

        private void CmbLocation_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (CmbLocation.SelectedIndex == 1)
            {
                using var fb = new System.Windows.Forms.FolderBrowserDialog();
                if (fb.ShowDialog(new WinformsWindow(this)) == System.Windows.Forms.DialogResult.OK)
                {
                    this.FileBrowsedEvent?.Invoke(this, new FileBrowsedEventArgs(fb.SelectedPath));
                }
                //var fc = new SaveFileDialog();
                //fc.Filter = "All files (*.*)|*.*";
                //fc.FileName = TxtFile.Text;
                //var ret = fc.ShowDialog(this);
                //if (ret.HasValue && ret.Value)
                //{
                //    this.FileBrowsedEvent?.Invoke(this, new FileBrowsedEventArgs(fc.FileName));
                //}
                else
                {
                    CmbLocation.SelectedIndex = previousIndex;
                }
            }
            else
            {
                previousIndex = CmbLocation.SelectedIndex;
                this.DropdownSelectionChangedEvent?.Invoke(this, new FileBrowsedEventArgs((string)CmbLocation.SelectedItem));
            }
        }

        public void DisposeWindow()
        {
            this.Close();
        }

        public void Invoke(Action callback)
        {
            Dispatcher.BeginInvoke(callback);
        }

        public void ShowWindow()
        {
            this.Show();
        }

        private void Window_Closed(object sender, EventArgs e)
        {
            this.DestroyEvent?.Invoke(this, EventArgs.Empty);
        }

        private void DontAddToQueueMenuItem_Click(object sender, RoutedEventArgs e)
        {
            this.DownloadLaterClicked?.Invoke(this, new DownloadLaterEventArgs(string.Empty));
        }

        private void ChkMp3_Checked(object sender, RoutedEventArgs e)
        {
            Mp3CheckChanged?.Invoke(this, EventArgs.Empty);
        }

        private void ChkMp3_Unchecked(object sender, RoutedEventArgs e)
        {
            Mp3CheckChanged?.Invoke(this, EventArgs.Empty);
        }

        private void QueueAndSchedulerMenuItem_Click(object sender, RoutedEventArgs e)
        {
            this.QueueSchedulerClicked?.Invoke(this, EventArgs.Empty);
        }

        public void ShowMessageBox(string text)
        {
            MessageBox.Show(this, text);
        }

        public void SetFolderValues(string[] values)
        {
            if (dropdownItems.Count > 0)
            {
                dropdownItems = new();
                CmbLocation.ItemsSource = dropdownItems;
            }
            previousIndex = 0;
            foreach (var item in values)
            {
                dropdownItems.Add(item);
            }
        }
    }
}
