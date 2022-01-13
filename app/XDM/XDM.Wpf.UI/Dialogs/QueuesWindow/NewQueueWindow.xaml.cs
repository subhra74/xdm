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
using XDM.Wpf.UI.Common;
using XDM.Wpf.UI.Win32;
using XDMApp;

namespace XDM.Wpf.UI.Dialogs.QueuesWindow
{
    /// <summary>
    /// Interaction logic for NewQueueWindow.xaml
    /// </summary>
    public partial class NewQueueWindow : Window, IDialog
    {
        public NewQueueWindow(IAppUI ui, 
            Action<DownloadQueue, bool> okAction, 
            DownloadQueue? modifyingQueue)
        {
            InitializeComponent();

            if (modifyingQueue == null)
            {
                this.TxtQueueName.Text = "New queue #" + QueueManager.QueueAutoNumber;
                QueueManager.QueueAutoNumber++;
            }
            else
            {
                this.TxtQueueName.Text = modifyingQueue.Name;
            }
            listView1.Items.Clear();
            listView1.CheckBoxes = true;
            var list = new List<InProgressDownloadEntry>(ui.GetAllInProgressDownloads());
            var set = new HashSet<string>();

            foreach (var queue in QueueManager.Queues)
            {
                foreach (var id in queue.DownloadIds)
                {
                    set.Add(id);
                }
            }

            foreach (var ent in list)
            {
                if (!set.Contains(ent.Id))
                {
                    var arr = new string[]
                    {
                    ent.Name,
                    ent.DateAdded.ToShortDateString(),
                    Helpers.FormatSize(ent.Size),
                    ent.Status==DownloadStatus.Downloading?$"{ent.Progress}%":ent.Status.ToString()
                    };
                    listView1.Items.Add(new ListViewItem(arr) { Checked = false, Tag = ent });
                }
            }

            button1.Click += (a, b) =>
            {
                if (string.IsNullOrEmpty(textBox1.Text))
                {
                    MessageBox.Show(TextResource.GetText("MSG_QUEUE_NAME_MISSING"));
                    return;
                }
                var list = new List<string>(listView1.CheckedItems.Count);
                foreach (ListViewItem lvi in listView1.CheckedItems)
                {
                    list.Add(((InProgressDownloadEntry)lvi.Tag).Id);
                }
                if (modifyingQueue == null)
                {
                    okAction.Invoke(new DownloadQueue(Guid.NewGuid().ToString(), textBox1.Text) { DownloadIds = list }, true);
                }
                else
                {
                    modifyingQueue.DownloadIds.AddRange(list);
                    okAction.Invoke(modifyingQueue, false);
                }
                Close();
            };

            button1.Margin = button2.Margin = checkBox1.Margin =
                new Padding(LogicalToDeviceUnits(3),
                LogicalToDeviceUnits(12),
                LogicalToDeviceUnits(12),
                LogicalToDeviceUnits(3));

            foreach (ColumnHeader col in listView1.Columns)
            {
                col.Width = col.Index == 0 ? LogicalToDeviceUnits(200) : LogicalToDeviceUnits(100);
            }
        }

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            NativeMethods.DisableMinMaxButton(this);
        }

        public bool Result { get; set; } = false;

        private void Chk_Checked(object sender, RoutedEventArgs e)
        {

        }
    }
}
