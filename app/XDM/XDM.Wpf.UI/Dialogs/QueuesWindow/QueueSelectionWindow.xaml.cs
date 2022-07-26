using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Interop;
using XDM.Core.UI;
using XDM.Wpf.UI.Common;
using XDM.Wpf.UI.Win32;
using System.Linq;
using XDM.Core;

namespace XDM.Wpf.UI.Dialogs.QueuesWindow
{
    /// <summary>
    /// Interaction logic for QueueSelectionWindow.xaml
    /// </summary>
    public partial class QueueSelectionWindow : Window, IDialog, IQueueSelectionDialog
    {
        public event EventHandler<QueueSelectionEventArgs>? QueueSelected;
        public event EventHandler? ManageQueuesClicked;
        private IEnumerable<string> downloadIds;
        private IEnumerable<string> queueIds;
        public bool Result { get; set; } = false;

        public QueueSelectionWindow()
        {
            InitializeComponent();
        }

        public void SetData(
            IEnumerable<string> queueNames,
            IEnumerable<string> queueIds,
            IEnumerable<string> downloadIds)
        {
            this.queueIds = queueIds;
            this.downloadIds = downloadIds;
            this.LbQueues.ItemsSource = queueNames;
            LbQueues.SelectedIndex = 0;
        }

        public void ShowWindow()
        {
            NativeMethods.ShowDialog(this, (Window)ApplicationContext.MainWindow);
        }

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            NativeMethods.DisableMinMaxButton(this);

#if NET45_OR_GREATER
            if (App.Skin == Skin.Dark)
            {
                var helper = new WindowInteropHelper(this);
                helper.EnsureHandle();
                DarkModeHelper.UseImmersiveDarkMode(helper.Handle, true);
            }
#endif
        }

        private void BtnOK_Click(object sender, RoutedEventArgs e)
        {
            var id = queueIds.ElementAt(LbQueues.SelectedIndex);
            QueueSelected?.Invoke(this, new QueueSelectionEventArgs(id, downloadIds));
            QueueSelected = null;
            Close();
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            QueueSelected = null;
            Close();
        }

        public void Dispose() { }
    }
}
