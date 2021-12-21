using System.Collections.Generic;
using System.Windows.Forms;
using Translations;

#if !(NET472_OR_GREATER || NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI
{
    public partial class PropertiesWindow : Form
    {
        public PropertiesWindow()
        {
            InitializeComponent();
            lvCookies.Columns[0].Width = LogicalToDeviceUnits(200);
            lvCookies.Columns[1].Width = LogicalToDeviceUnits(200);

            lvHeaders.Columns[0].Width = LogicalToDeviceUnits(200);
            lvHeaders.Columns[1].Width = LogicalToDeviceUnits(200);

            LoadTexts();
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

        private void LoadTexts()
        {
            Text = TextResource.GetText("MENU_PROPERTIES");
            lblFile.Text = TextResource.GetText("SORT_NAME");
            lblFolder.Text = TextResource.GetText("LBL_SAVE_IN");
            lblUrl.Text = TextResource.GetText("ND_ADDRESS");
            lblSize.Text = TextResource.GetText("SORT_SIZE");
            lblDate.Text = TextResource.GetText("SORT_DATE");
            lblType.Text = TextResource.GetText("SORT_TYPE");
            lblReferer.Text = TextResource.GetText("PROP_REFERER");
            lblCookies.Text = TextResource.GetText("PROP_COOKIE");
            lblHeader.Text = TextResource.GetText("MSG_HEADERS");
        }
    }
}
