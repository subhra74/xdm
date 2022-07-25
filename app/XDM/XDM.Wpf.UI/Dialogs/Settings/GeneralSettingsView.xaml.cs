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
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Navigation;
using System.Windows.Shapes;
using XDM.Core.Lib.Common;
using XDM.Wpf.UI.Win32;
using WinForms = System.Windows.Forms;
using XDM.Core.Lib.UI;

namespace XDM.Wpf.UI.Dialogs.Settings
{
    /// <summary>
    /// Interaction logic for GeneralSettingsView.xaml
    /// </summary>
    public partial class GeneralSettingsView : UserControl, ISettingsPage
    {
        public IAppService App { get; set; }
        public Window Window { get; set; }

        private ObservableCollection<Category> categories = new ObservableCollection<Category>();

        public GeneralSettingsView()
        {
            InitializeComponent();
            CmbMaxParallalDownloads.ItemsSource = Enumerable.Range(1, 50);
        }

        public void PopulateUI()
        {
            ChkShowPrg.IsChecked = Config.Instance.ShowProgressWindow;
            ChkShowComplete.IsChecked = Config.Instance.ShowDownloadCompleteWindow;
            ChkStartAuto.IsChecked = Config.Instance.StartDownloadAutomatically;
            ChkOverwrite.IsChecked = Config.Instance.FileConflictResolution == FileConflictResolution.Overwrite;
            ChkDarkTheme.IsChecked = Config.Instance.AllowSystemDarkTheme;
            TxtTempFolder.Text = Config.Instance.TempDir;
            CmbMaxParallalDownloads.SelectedItem = Config.Instance.MaxParallelDownloads;
            ChkAutoCat.IsChecked = Config.Instance.FolderSelectionMode == FolderSelectionMode.Auto;
            TxtDownloadFolder.Text = Config.Instance.DefaultDownloadFolder;
            CmbDblClickAction.SelectedIndex = Config.Instance.DoubleClickOpenFile ? 1 : 0;

            foreach (var cat in Config.Instance.Categories)
            {
                categories.Add(cat);
            }
            LvCategories.ItemsSource = categories;
        }

        public void UpdateConfig()
        {
            Config.Instance.ShowProgressWindow = ChkShowPrg.IsChecked ?? false;
            Config.Instance.ShowDownloadCompleteWindow = ChkShowComplete.IsChecked ?? false;
            Config.Instance.StartDownloadAutomatically = ChkStartAuto.IsChecked ?? false;
            Config.Instance.FileConflictResolution =
                ChkOverwrite.IsChecked.HasValue && ChkOverwrite.IsChecked.Value ? FileConflictResolution.Overwrite : FileConflictResolution.AutoRename;
            Config.Instance.TempDir = TxtTempFolder.Text;
            Config.Instance.MaxParallelDownloads = (int)CmbMaxParallalDownloads.SelectedItem;

            Config.Instance.Categories = new List<Category>(this.categories);
            Config.Instance.FolderSelectionMode = ChkAutoCat.IsChecked.HasValue && ChkAutoCat.IsChecked.Value ?
                FolderSelectionMode.Auto : FolderSelectionMode.Manual;
            Config.Instance.DefaultDownloadFolder = TxtDownloadFolder.Text;
            Config.Instance.AllowSystemDarkTheme = ChkDarkTheme.IsChecked ?? false;
            Config.Instance.DoubleClickOpenFile = CmbDblClickAction.SelectedIndex == 1;
        }

        private void CatAdd_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new CategoryEditWindow { Owner = Window };
            var ret = dlg.ShowDialog(Window);
            if (ret.HasValue && ret.Value)
            {
                categories.Add(new Category
                {
                    Name = Guid.NewGuid().ToString(),
                    DisplayName = dlg.CategoryName,
                    DefaultFolder = dlg.Folder,
                    FileExtensions = new HashSet<string>(dlg.FileTypes.Replace("\r\n", "")
                    .Split(',').Select(x => x.Trim()).Where(x => x.Length > 0))
                });
            }
        }

        private void CatEdit_Click(object sender, RoutedEventArgs e)
        {
            var index = LvCategories.SelectedIndex;
            if (index >= 0)
            {
                var cat = categories[index];
                var dlg = new CategoryEditWindow { Owner = Window };
                dlg.SetCategory(categories[index]);
                var ret = dlg.ShowDialog(Window);
                if (ret.HasValue && ret.Value)
                {
                    categories[index] = new Category
                    {
                        Name = cat.Name,
                        DisplayName = dlg.CategoryName,
                        DefaultFolder = dlg.Folder,
                        FileExtensions = new HashSet<string>(dlg.FileTypes.Replace("\r\n", "")
                        .Split(',').Select(x => x.Trim()).Where(x => x.Length > 0))
                    };
                }
            }
        }

        private void CatDel_Click(object sender, RoutedEventArgs e)
        {
            var index = LvCategories.SelectedIndex;
            if (index >= 0)
            {
                categories.RemoveAt(index);
            }
        }

        private void CatDef_Click(object sender, RoutedEventArgs e)
        {
            var items = new List<Category>(this.categories);
            foreach (var cat in items)
            {
                this.categories.Remove(cat);
            }
            foreach (var cat in Config.DefaultCategories)
            {
                this.categories.Add(cat);
            }
        }

        private void BtnTempFolderBrowse_Click(object sender, RoutedEventArgs e)
        {
            using var folderBrowser = new WinForms.FolderBrowserDialog();
            if (folderBrowser.ShowDialog() == WinForms.DialogResult.OK)
            {
                TxtTempFolder.Text = folderBrowser.SelectedPath;
            }
        }

        private void BtnDownloadFolderBrowse_Click(object sender, RoutedEventArgs e)
        {
            using var folderBrowser = new WinForms.FolderBrowserDialog();
            if (folderBrowser.ShowDialog() == WinForms.DialogResult.OK)
            {
                TxtDownloadFolder.Text = folderBrowser.SelectedPath;
            }
        }
    }
}
