using System.Collections.Generic;
using System.Windows.Forms;

namespace XDM.WinForm.UI
{
    public partial class PropertiesWindow : Form
    {
        public PropertiesWindow()
        {
            InitializeComponent();
            lvCookies.Columns[0].Width = 200;
            lvCookies.Columns[1].Width = 200;

            lvHeaders.Columns[0].Width = 200;
            lvHeaders.Columns[1].Width = 200;
        }

        public string FileName { set => txtFile.Text = value; }
        public string Folder { set => txtFolder.Text = value; }
        public string Address { set => txtUrl.Text = value; }
        public string FileSize { set => lblSizeValue.Text = value; }
        public string DateAdded { set => lblDateValue.Text = value; }
        public string DownloadType { set => lblTypeValue.Text = value; }
        public string Referer { set => txtReferer.Text = value; }
        public Dictionary<string, string> Cookies
        {
            set
            {
                if (value != null)
                {
                    foreach (var key in value.Keys)
                    {
                        var item = new ListViewItem
                        {
                            Text = key
                        };
                        item.SubItems.Add(value[key]);
                        lvCookies.Items.Add(item);
                    }
                }
            }
        }
        public Dictionary<string, List<string>> Headers
        {
            set
            {
                if (value != null)
                {
                    foreach (var key in value.Keys)
                    {
                        foreach (var val in value[key])
                        {
                            var item = new ListViewItem
                            {
                                Text = key
                            };
                            item.SubItems.Add(val);
                            lvHeaders.Items.Add(item);
                        }
                    }
                }
            }
        }
    }
}
