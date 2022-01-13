using System;

using System.Windows.Forms;
using Translations;
using XDM.Core.Lib.Common;

namespace XDM.WinForm.UI
{
    public partial class LinkRefreshWindow : Form, IRefreshLinkDialogSkeleton
    {
        public LinkRefreshWindow()
        {
            InitializeComponent();
            Text = TextResource.GetText("MENU_REFRESH_LINK");
            label1.Text = TextResource.GetText("REF_WAITING_FOR_LINK");
            button1.Text = TextResource.GetText("BTN_STOP_PROCESSING");
        }

        public event EventHandler? WatchingStopped;

        public void ShowWindow()
        {
            ShowDialog();
        }

        protected override void OnClosed(EventArgs e)
        {
            base.OnClosed(e);
            WatchingStopped?.Invoke(this, e);
        }

        public void LinkReceived()
        {
            BeginInvoke(new Action(() => Dispose()));
        }
    }
}
