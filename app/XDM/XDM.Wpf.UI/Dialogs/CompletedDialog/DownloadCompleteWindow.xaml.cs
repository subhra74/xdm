using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using XDM.Core.Lib.Common;

namespace XDM.Wpf.UI.Dialogs.CompletedDialog
{
    /// <summary>
    /// Interaction logic for DownloadCompleteWindow.xaml
    /// </summary>
    public partial class DownloadCompleteWindow : Window, IDownloadCompleteDialog
    {
        public IApp App { get; set; }
        public event EventHandler<DownloadCompleteDialogEventArgs> FileOpenClicked;
        public event EventHandler<DownloadCompleteDialogEventArgs> FolderOpenClicked;

        public string FileNameText
        {
            get => TxtFileName.Text;
            set => TxtFileName.Text = value;
        }

        public string FolderText
        {
            get => TxtLocation.Text;
            set => TxtLocation.Text = value;
        }

        public DownloadCompleteWindow()
        {
            InitializeComponent();
        }

        private void TxtDontShowCompleteDialog_MouseDown(object sender, MouseButtonEventArgs e)
        {
            FolderOpenClicked?.Invoke(sender, new DownloadCompleteDialogEventArgs
            {
                Path = TxtLocation.Text,
                FileName = TxtFileName.Text
            });
            Close();
        }

        private void BtnOpen_Click(object sender, RoutedEventArgs e)
        {
            FileOpenClicked?.Invoke(sender, new DownloadCompleteDialogEventArgs
            {
                Path = System.IO.Path.Combine(TxtLocation.Text, TxtFileName.Text)
            });
            Close();
        }

        public void ShowDownloadCompleteDialog()
        {
            this.Show();
        }
    }
}
