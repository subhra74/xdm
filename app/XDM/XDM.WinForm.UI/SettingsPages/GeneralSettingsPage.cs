
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Drawing.Text;
using System.Linq;
using System.Text;

using System.Windows.Forms;
using XDM.Core.Lib.Common;
using XDM.WinForm.UI.FormHelper;
using XDMApp;

namespace XDM.WinForm.UI.SettingsPages
{
    public partial class GeneralSettingsPage : UserControl, ISettingsPage
    {
        private Font ri16Font;
        public GeneralSettingsPage()
        {
            InitializeComponent();
            this.ri16Font = new Font(GlobalFontCollection.RiFontInstance.Families[0], 12);
            AutoScrollMinSize = tableLayoutPanel1.Size;
            listView1.Columns[0].Width = DpiCompat.ToDeviceUnits(this, 150);
            listView1.Columns[1].Width = DpiCompat.ToDeviceUnits(this, 150);
            button1.Font = this.ri16Font;
            button1.Text = RemixIcon.GetFontIcon("ed70");
            button6.Font = this.ri16Font;
            button6.Text = RemixIcon.GetFontIcon("ed70");
        }

        public void PopulateUI()
        {
            checkBox1.Checked = Config.Instance.ShowProgressWindow;
            checkBox2.Checked = Config.Instance.ShowDownloadCompleteWindow;
            checkBox3.Checked = Config.Instance.StartDownloadAutomatically;
            checkBox4.Checked = Config.Instance.FileConflictResolution == FileConflictResolution.Overwrite;
            checkBox6.Checked = Config.Instance.AllowSystemDarkTheme;
            textBox1.Text = Config.Instance.TempDir;
            numericUpDown1.Value = Config.Instance.MaxParallelDownloads;

            listView1.SuspendLayout();
            foreach (var cat in Config.Instance.Categories)
            {
                var lvi = new ListViewItem
                {
                    Text = cat.DisplayName
                };
                lvi.SubItems.Add(string.Join(",", cat.FileExtensions.ToArray()));
                lvi.SubItems.Add(cat.DefaultFolder);
                lvi.Tag = cat;
                listView1.Items.Add(lvi);
            }
            listView1.ResumeLayout();

            checkBox5.Checked = Config.Instance.FolderSelectionMode == FolderSelectionMode.Auto;

            //radioButton1.CheckedChanged += (_, _) =>
            //{
            //    if (radioButton1.Checked)
            //    {
            //        textBox2.Enabled = false;
            //        button6.Enabled = false;
            //    }
            //};

            //radioButton2.CheckedChanged += (_, _) =>
            //{
            //    if (radioButton2.Checked)
            //    {
            //        textBox2.Enabled = true;
            //        button6.Enabled = true;
            //    }
            //};

            //if (Config.Instance.FolderSelectionMode == FolderSelectionMode.SingleFolder)
            //{
            //    radioButton2.Checked = true;
            //    radioButton1.Checked = false;
            //}
            //else
            //{
            //    radioButton2.Checked = false;
            //    radioButton1.Checked = true;
            //}

            this.textBox2.Text = Config.Instance.DefaultDownloadFolder;
        }

        public void UpdateConfig()
        {
            Config.Instance.ShowProgressWindow = checkBox1.Checked;
            Config.Instance.ShowDownloadCompleteWindow = checkBox2.Checked;
            Config.Instance.StartDownloadAutomatically = checkBox3.Checked;
            Config.Instance.FileConflictResolution =
                checkBox4.Checked ? FileConflictResolution.Overwrite : FileConflictResolution.AutoRename;
            Config.Instance.TempDir = textBox1.Text;
            Config.Instance.MaxParallelDownloads = (int)numericUpDown1.Value;

            var categories = new List<Category>();
            foreach (ListViewItem lvi in listView1.Items)
            {
                categories.Add((Category)lvi.Tag);
            }
            Config.Instance.Categories = categories;
            Config.Instance.FolderSelectionMode = checkBox5.Checked ? FolderSelectionMode.Auto : FolderSelectionMode.Manual;
            Config.Instance.DefaultDownloadFolder = textBox2.Text;
            Config.Instance.AllowSystemDarkTheme = checkBox6.Checked;
        }

        private void button2_Click(object sender, EventArgs e)
        {
            listView1.Items.Clear();
            listView1.SuspendLayout();
            foreach (var cat in Config.DefaultCategories)
            {
                var lvi = new ListViewItem
                {
                    Text = cat.DisplayName
                };
                lvi.SubItems.Add(string.Join(",", cat.FileExtensions.ToArray()));
                lvi.Tag = cat;
                listView1.Items.Add(lvi);
            }
            listView1.ResumeLayout();
        }

        private void button5_Click(object sender, EventArgs e)
        {
            using var categoryDlg = new CategoryDialog();
            if (categoryDlg.ShowDialog(this) == DialogResult.OK)
            {
                var cat = new Category
                {
                    DisplayName = categoryDlg.CategoryName,
                    Name = categoryDlg.CategoryName,
                    FileExtensions = new HashSet<string>(categoryDlg.FileTypes)
                };
                var lvi = new ListViewItem
                {
                    Text = cat.DisplayName
                };
                lvi.SubItems.Add(string.Join(",", cat.FileExtensions.ToArray()));
                lvi.Tag = cat;
                listView1.Items.Add(lvi);
            }
        }

        private void button4_Click(object sender, EventArgs e)
        {
            if (listView1.SelectedItems.Count == 0) return;
            var index = listView1.SelectedIndices[0];
            var lvi = listView1.SelectedItems[0];
            var cat = (Category)lvi.Tag;

            using var categoryDlg = new CategoryDialog
            {
                CategoryName = cat.DisplayName,
                FileTypes = cat.FileExtensions
            };
            if (categoryDlg.ShowDialog(this) == DialogResult.OK)
            {
                cat = new Category
                {
                    DisplayName = categoryDlg.CategoryName,
                    Name = categoryDlg.CategoryName,
                    FileExtensions = new HashSet<string>(categoryDlg.FileTypes)
                };
                lvi.SubItems.Clear();
                lvi.Text = cat.DisplayName;
                lvi.SubItems.Add(string.Join(",", cat.FileExtensions.ToArray()));
                //lvi.SubItems[0].Text = string.Join(',', cat.FileExtensions);
                //lvi.SubItems[1].Text = string.Join(',', cat.FileExtensions);
                lvi.Tag = cat;
            }
        }

        private void button3_Click(object sender, EventArgs e)
        {
            if (listView1.SelectedIndices.Count == 0) return;
            var selectedIndex = listView1.SelectedIndices[0];
            listView1.Items.RemoveAt(selectedIndex);
        }

        private void button1_Click(object sender, EventArgs e)
        {
            using var folderBrowser = new FolderBrowserDialog();
            if (folderBrowser.ShowDialog(this) == DialogResult.OK)
            {
                textBox1.Text = folderBrowser.SelectedPath;
            }
        }

        private void button6_Click(object sender, EventArgs e)
        {
            using var fb = new FolderBrowserDialog();
            if (fb.ShowDialog(this) == DialogResult.OK)
            {
                textBox2.Text = fb.SelectedPath;
            }
        }
    }
}
