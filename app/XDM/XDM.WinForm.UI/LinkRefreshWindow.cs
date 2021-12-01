using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Text;

using System.Windows.Forms;
using XDM.Core.Lib.Common;

namespace XDM.WinForm.UI
{
    public partial class LinkRefreshWindow : Form, IRefreshLinkDialogSkeleton
    {
        public LinkRefreshWindow()
        {
            InitializeComponent();
        }

        public event EventHandler WatchingStopped;

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
