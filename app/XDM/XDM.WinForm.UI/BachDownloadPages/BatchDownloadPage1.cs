using TraceLog;
using System;
using System.Drawing;
using System.Linq;
using System.Windows.Forms;
using Translations;

#if !(NET472_OR_GREATER || NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI.BachDownloadPages
{
    public partial class BatchDownloadPage1 : UserControl
    {
        internal event EventHandler<BatchLinkEventArgs> LinksAdded;
        internal event EventHandler Cancelled;
        private BatchDownloadSubPage1 subPage;

        public BatchDownloadPage1()
        {
            InitializeComponent();
            subPage = new BatchDownloadSubPage1
            {
                Dock = DockStyle.Fill
            };
            this.tabControl1.TabPages[0].Controls.Add(subPage);
            this.tabControl1.Padding = new Point(LogicalToDeviceUnits(10), LogicalToDeviceUnits(5));
            this.tabControl1.Margin = new Padding(0, 0, 0, 10);
            this.button1.Padding = this.button2.Padding = new Padding(LogicalToDeviceUnits(10), LogicalToDeviceUnits(2)
                , LogicalToDeviceUnits(10), LogicalToDeviceUnits(2));
            this.Padding = new Padding(LogicalToDeviceUnits(10));
            this.label1.Padding = new Padding(0, LogicalToDeviceUnits(10), 0, LogicalToDeviceUnits(10));
            LoadTexts();
        }

        private void button1_Click(object sender, EventArgs e)
        {
            if (tabControl1.SelectedIndex == 0)
            {
                if (subPage.BatchSize == 0)
                {
                    MessageBox.Show(this, TextResource.GetText("BAT_NO_LINK"));
                    return;
                }
                LinksAdded?.Invoke(this, new BatchLinkEventArgs
                {
                    Links = subPage.GenerateBatchLink()
                });
            }
            if (tabControl1.SelectedIndex == 1)
            {
                try
                {
                    var uris = textBox1.Text.Split('\r', '\n').Where(x => x.Length > 0).Select(x => new Uri(x)).ToList();
                    if (uris.Count < 1)
                    {
                        MessageBox.Show(this, TextResource.GetText("BAT_NO_LINK"));
                        return;
                    }
                    LinksAdded?.Invoke(this, new BatchLinkEventArgs
                    {
                        Links = uris
                    });
                }
                catch (UriFormatException)
                {
                    MessageBox.Show(this, TextResource.GetText("MSG_INVALID_URL"));
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, "Error");
                }
            }

        }

        private void button2_Click(object sender, EventArgs e)
        {
            Cancelled?.Invoke(this, EventArgs.Empty);
        }

        private void LoadTexts()
        {
            tabPage1.Text = TextResource.GetText("BAT_PATTERN");
            tabPage2.Text = TextResource.GetText("BAT_LINKS");
            button1.Text= TextResource.GetText("MSG_OK");
            button2.Text= TextResource.GetText("ND_CANCEL");
            label1.Text= TextResource.GetText("BAT_PASTE_LINK");
        }
    }
}
