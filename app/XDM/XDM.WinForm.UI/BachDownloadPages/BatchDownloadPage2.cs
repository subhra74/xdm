using TraceLog;
using System;
using System.Collections.Generic;
using System.Windows.Forms;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Util;

#if !(NET472_OR_GREATER||NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI.BachDownloadPages
{
    public partial class BatchDownloadPage2 : UserControl
    {
        internal event EventHandler<BatchLinkDownloadEventArgs> DownloadNow;
        internal event EventHandler<BatchLinkDownloadEventArgs> DownloadLater;
        internal event EventHandler Cancelled;

        private AuthenticationInfo? authentication;
        private ProxyInfo? proxy = Config.Instance.Proxy;
        private int speedLimit = Config.Instance.DefaltDownloadSpeed;
        private bool enableSpeedLimit = Config.Instance.EnableSpeedLimit;
        private IAppUI appUI;

        public BatchDownloadPage2(IAppUI appUI)
        {
            InitializeComponent();
            this.appUI = appUI;
            textBox1.Text = Helpers.GetDownloadFolderByFileName(null);
            var padding1 = new Padding(LogicalToDeviceUnits(5), LogicalToDeviceUnits(2),
                LogicalToDeviceUnits(5), LogicalToDeviceUnits(2));
            var padding2 = new Padding(LogicalToDeviceUnits(5));
            label1.Padding = label2.Padding = textBox1.Margin = button3.Margin = padding2;
            button1.Padding = button4.Padding = button5.Padding = button2.Padding = padding1;
            this.Padding = new Padding(LogicalToDeviceUnits(10));
        }

        public void SetBatchLinks(IEnumerable<Uri> links)
        {
            try
            {
                foreach (var link in links)
                {
                    checkedListBox1.Items.Add(link, true);
                }
            }
            catch (UriFormatException)
            {
                MessageBox.Show("Invalid url");
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error");
            }
        }

        private IEnumerable<Uri> GetSelectedLinks()
        {
            foreach (Uri item in checkedListBox1.CheckedItems)
            {
                yield return item;
            }
        }

        private void button2_Click(object sender, EventArgs e)
        {
            DownloadNow?.Invoke(this, new BatchLinkDownloadEventArgs
            {
                Links = GetSelectedLinks(),
                TargetFolder = textBox1.Text,
                Authentication = authentication,
                QueueId = null,
                EnableSpeedLimit = enableSpeedLimit,
                SpeedLimit = speedLimit,
                Proxy = proxy
            });
        }

        private void button1_Click(object sender, EventArgs e)
        {
            DownloadLaterMenuHelper.PopulateMenuAndAttachEvents(
                   contextMenuStrip1,
                   (s, e) =>
                   {
                       DownloadLater?.Invoke(this, new BatchLinkDownloadEventArgs
                       {
                           Links = GetSelectedLinks(),
                           TargetFolder = textBox1.Text,
                           Authentication = authentication,
                           QueueId = e.QueueId,
                           EnableSpeedLimit = enableSpeedLimit,
                           SpeedLimit = speedLimit,
                           Proxy = proxy
                       });
                   },
                   doNotAddToQueueToolStripMenuItem,
                   manageQueueAndSchedulersToolStripMenuItem,
                   button2,
                   this);
        }

        private void button4_Click(object sender, EventArgs e)
        {
            Cancelled?.Invoke(this, EventArgs.Empty);
        }

        private void button5_Click(object sender, EventArgs e)
        {
            AdvancedDialogHelper.Show(ref authentication, ref proxy, ref enableSpeedLimit, ref speedLimit, this);
        }

        private void doNotAddToQueueToolStripMenuItem_Click(object sender, EventArgs e)
        {
            DownloadLater?.Invoke(this, new BatchLinkDownloadEventArgs
            {
                Links = GetSelectedLinks(),
                TargetFolder = textBox1.Text,
                Authentication = authentication,
                QueueId = null,
                EnableSpeedLimit = enableSpeedLimit,
                SpeedLimit = speedLimit,
                Proxy = proxy
            });
        }

        private void manageQueueAndSchedulersToolStripMenuItem_Click(object sender, EventArgs e)
        {
            appUI.ShowQueueWindow(this.ParentForm);
        }
    }
}
