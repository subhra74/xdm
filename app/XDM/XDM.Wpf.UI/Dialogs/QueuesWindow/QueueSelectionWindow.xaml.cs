using System;
using System.Collections.Generic;
using System.Windows;
using XDM.Core.Lib.UI;
using XDM.Wpf.UI.Common;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.QueuesWindow
{
    /// <summary>
    /// Interaction logic for QueueSelectionWindow.xaml
    /// </summary>
    public partial class QueueSelectionWindow : Window, IDialog, IQueueSelectionDialog
    {
        public event EventHandler<QueueSelectionEventArgs>? QueueSelected;
        public event EventHandler? ManageQueuesClicked;
        private string[] downloadIds = new string[0];
        public bool Result { get; set; } = false;

        public QueueSelectionWindow()
        {
            InitializeComponent();
        }

        public void SetData(IEnumerable<string> items, string[] downloadIds)
        {
            this.downloadIds = downloadIds;
            this.LbQueues.ItemsSource = items;
            LbQueues.SelectedIndex = 0;
        }

        public void ShowWindow(IAppWinPeer peer)
        {
            NativeMethods.ShowDialog(this, (Window)peer);
        }

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            NativeMethods.DisableMinMaxButton(this);
        }

        private void BtnOK_Click(object sender, RoutedEventArgs e)
        {
            QueueSelected?.Invoke(this, new QueueSelectionEventArgs(LbQueues.SelectedIndex, downloadIds));
            QueueSelected = null;
            Close();
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            QueueSelected = null;
            Close();
        }
    }
}
