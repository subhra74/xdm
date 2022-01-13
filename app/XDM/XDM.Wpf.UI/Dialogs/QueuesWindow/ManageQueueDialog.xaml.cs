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
    public partial class ManageQueueDialog : Window, IDialog
    {
        private IAppUI appUI;
        private DownloadSchedule defaultSchedule;

        public event EventHandler<QueueListEventArgs>? QueuesModified;
        public event EventHandler<DownloadListEventArgs>? QueueStartRequested;
        public event EventHandler<DownloadListEventArgs>? QueueStopRequested;
        public event EventHandler? WindowClosing;

        public ManageQueueDialog()
        {
            InitializeComponent();
        }

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            NativeMethods.DisableMinMaxButton(this);
        }

        public bool Result { get; set; } = false;
    }
}
