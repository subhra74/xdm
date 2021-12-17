using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Drawing.Text;
using System.Linq;
using System.Text;

using System.Windows.Forms;
using Translations;
using XDM.Core.Lib.Common;

#if !(NET472_OR_GREATER||NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI.SettingsPages
{
    public partial class PasswordManagerPage : UserControl, ISettingsPage
    {
        public PasswordManagerPage()
        {
            InitializeComponent();
            foreach (ColumnHeader col in listView1.Columns)
            {
                col.Width = LogicalToDeviceUnits(150);
            }

            listView1.Margin = new Padding(LogicalToDeviceUnits(10), LogicalToDeviceUnits(10),
                LogicalToDeviceUnits(10), LogicalToDeviceUnits(5));
            flowLayoutPanel1.Margin = new Padding(LogicalToDeviceUnits(10), LogicalToDeviceUnits(10),
                LogicalToDeviceUnits(10), LogicalToDeviceUnits(5));

            LoadTexts();
        }

        public void PopulateUI()
        {
            listView1.SuspendLayout();
            foreach (var item in Config.Instance.UserCredentials)
            {
                var lvi = new ListViewItem
                {
                    Text = item.Host
                };
                lvi.SubItems.Add(item.User);
                lvi.SubItems.Add(item.Password);
                lvi.Tag = item;
                listView1.Items.Add(lvi);
            }
            listView1.ResumeLayout();
        }

        public void UpdateConfig()
        {
            var credentials = new List<PasswordEntry>();
            foreach (ListViewItem lvi in listView1.Items)
            {
                credentials.Add((PasswordEntry)lvi.Tag);
            }
            Config.Instance.UserCredentials = credentials;
        }

        private void button1_Click(object sender, EventArgs e)
        {
            using var passwordDlg = new PasswordWindow();
            if (passwordDlg.ShowDialog(this) == DialogResult.OK)
            {
                var item = passwordDlg.PasswordEntry;
                var lvi = new ListViewItem
                {
                    Text = item.Host
                };
                lvi.SubItems.Add(item.User);
                lvi.SubItems.Add("***");
                lvi.Tag = item;
                listView1.Items.Add(lvi);
            }
        }

        private void button2_Click(object sender, EventArgs e)
        {
            if (listView1.SelectedItems.Count == 0) return;
            var lvi = listView1.SelectedItems[0];
            var ent = (PasswordEntry)lvi.Tag;

            using var passwordDlg = new PasswordWindow
            {
                PasswordEntry = ent
            };
            if (passwordDlg.ShowDialog(this) == DialogResult.OK)
            {
                ent = passwordDlg.PasswordEntry;
                lvi.SubItems.Clear();
                lvi.Text = ent.Host;
                lvi.SubItems.Add(ent.User);
                lvi.SubItems.Add("***");
                lvi.Tag = ent;
            }
        }

        private void button3_Click(object sender, EventArgs e)
        {
            if (listView1.SelectedIndices.Count == 0) return;
            var selectedIndex = listView1.SelectedIndices[0];
            listView1.Items.RemoveAt(selectedIndex);
        }

        private void LoadTexts()
        {
            button1.Text = TextResource.GetText("SETTINGS_CAT_ADD");
            button2.Text = TextResource.GetText("SETTINGS_CAT_EDIT");
            button3.Text = TextResource.GetText("DESC_DEL");

            listView1.Columns[0].Text = TextResource.GetText("DESC_HOST");
            listView1.Columns[1].Text = TextResource.GetText("DESC_USER");
            listView1.Columns[2].Text = TextResource.GetText("DESC_PASS");
        }
    }
}
