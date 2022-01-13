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
using XDM.Core.Lib.UI;
using XDM.Wpf.UI.Common;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.QueuesWindow
{
    /// <summary>
    /// Interaction logic for ManageQueueDialog.xaml
    /// </summary>
    public partial class ManageQueueDialog : Window, IDialog, IQueuesWindow
    {
        private IAppUI appUI;
        private DownloadSchedule defaultSchedule;

        public event EventHandler<QueueListEventArgs>? QueuesModified;
        public event EventHandler<DownloadListEventArgs>? QueueStartRequested;
        public event EventHandler<DownloadListEventArgs>? QueueStopRequested;
        public event EventHandler? WindowClosing;

        public ManageQueueDialog(IAppUI appUI)
        {
            InitializeComponent();
            this.appUI = appUI;
        }

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            NativeMethods.DisableMinMaxButton(this);
        }

        public bool Result { get; set; } = false;

        private void BtnNew_Click(object sender, RoutedEventArgs e)
        {
            new NewQueueWindow(this.appUI, (a, b) => { }, null) { Owner = this }.ShowDialog(this);
        }

        public void RefreshView()
        {
            //throw new NotImplementedException();
        }

        public void SetData(IEnumerable<DownloadQueue> queues)
        {
            //throw new NotImplementedException();
        }

        public void ShowWindow(object peer)
        {
            this.Owner = (Window)peer;
            NativeMethods.ShowDialog(this, (Window)peer);
        }
    }
}
