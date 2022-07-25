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
using Translations;
using XDM.Core;
using XDM.Wpf.UI.Common;
using XDM.Wpf.UI.Win32;
using WinForms = System.Windows.Forms;

namespace XDM.Wpf.UI.Dialogs.Settings
{
    /// <summary>
    /// Interaction logic for CategoryEditWindow.xaml
    /// </summary>
    public partial class CategoryEditWindow : Window, IDialog
    {
        public bool Result { get; set; } = false;

        public CategoryEditWindow()
        {
            InitializeComponent();
            this.DataContext = this;
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

        public string CategoryName
        {
            get => TxtName.Text;
            set => TxtName.Text = value;
        }

        public string FileTypes
        {
            get => TxtFileTypes.Text;
            set => TxtFileTypes.Text = value;
        }

        public string Folder { get => TxtFolder.Text; set => TxtFolder.Text = value; }

        public void SetCategory(Category category)
        {
            this.TxtName.Text = category.DisplayName;
            this.TxtFileTypes.Text = string.Join(",", category.FileExtensions.ToArray());
            this.TxtFolder.Text = category.DefaultFolder;
        }

        private void Browse_Click(object sender, RoutedEventArgs e)
        {
            using var fd = new WinForms.FolderBrowserDialog();
            if (!string.IsNullOrEmpty(TxtFolder.Text))
            {
                fd.SelectedPath = TxtFolder.Text;
            }
            if (fd.ShowDialog() == WinForms.DialogResult.OK && !string.IsNullOrEmpty(fd.SelectedPath))
            {
                TxtFolder.Text = fd.SelectedPath;
            }
        }

        private void BtnOk_Click(object sender, RoutedEventArgs e)
        {
            if (string.IsNullOrEmpty(CategoryName))
            {
                MessageBox.Show(TextResource.GetText("MSG_CAT_NAME_MISSING"));
                return;
            }
            if (string.IsNullOrEmpty(FileTypes))
            {
                MessageBox.Show(TextResource.GetText("MSG_CAT_FILE_TYPES_MISSING"));
                return;
            }
            if (string.IsNullOrEmpty(Folder))
            {
                MessageBox.Show(TextResource.GetText("MSG_CAT_FOLDER_MISSING"));
                return;
            }
            Result = true;
            Close();
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            Result = false;
            Close();
        }
    }
}
