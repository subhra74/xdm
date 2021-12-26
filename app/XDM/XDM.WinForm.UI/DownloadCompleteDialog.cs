using System;
using System.Drawing;
using System.Drawing.Text;
using System.IO;

using System.Windows.Forms;
using Translations;
using XDM.Core.Lib.Common;
using XDM.WinForm.UI.FormHelper;
using XDMApp;

namespace XDM.WinForm.UI
{
    public partial class DownloadCompleteDialog : Form, IDownloadCompleteDialog
    {
        private PrivateFontCollection fontCollection;
        private Font fontAwesomeFont;
        private string downloadId;
        private IFormColors colors;

        public IApp App { get; set; }
        public event EventHandler<DownloadCompleteDialogEventArgs> FileOpenClicked;
        public event EventHandler<DownloadCompleteDialogEventArgs> FolderOpenClicked;

        public string FileNameText
        {
            get => label2.Text;
            set => label2.Text = value;
        }

        public string FolderText
        {
            get => label3.Text;
            set => label3.Text = value;
        }

        public DownloadCompleteDialog()
        {
            InitializeComponent();
            fontCollection = new PrivateFontCollection();
            //fontCollection.AddFontFile("fontawesome-webfont.ttf");

            fontCollection.AddFontFile(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, @"FontAwesome\remixicon.ttf"));
            //fontCollection.AddFontFile(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "FontAwesome", "fa-solid-900.ttf"));
            fontAwesomeFont = new Font(fontCollection.Families[0], 32);

            lblMainIcon.Font = fontAwesomeFont;
            lblMainIcon.Text = RemixIcon.GetFontIcon("ecd9");// ((char)Int32.Parse("ecd9"/*"ec28"*//*"eb99"*/, System.Globalization.NumberStyles.HexNumber)).ToString();

            label1.Font = new Font(fontCollection.Families[0], 16); ;
            label1.Text = RemixIcon.GetFontIcon("ec60"); //ri-drag-move-2-line

            if (!AppWinPeer.AppsUseLightTheme)
            {
                colors = new FormColorsDark();
                if (!this.IsHandleCreated)
                {
                    this.CreateHandle();
                }

                DarkModeHelper.UseImmersiveDarkMode(this.Handle, true);
                this.lblMainIcon.ForeColor = colors.SearchButtonColor;
                this.lblMainIcon.BackColor = colors.ToolbarBackColor;
                label2.ForeColor = label3.ForeColor = colors.ToolbarButtonForeColor;
                label2.BackColor = label3.BackColor = colors.ToolbarBackColor;
                DarkModeHelper.StyleFlatButton(button1, colors);
                DarkModeHelper.StyleFlatButton(button2, colors);
                DarkModeHelper.StyleFlatButton(button3, colors);
                tableLayoutPanel2.BackColor = colors.DataGridViewBackColor;
                tableLayoutPanel1.BackColor = colors.ToolbarBackColor;
            }

            LoadTexts();
        }

        private void button2_Click(object sender, EventArgs e)
        {
            FileOpenClicked?.Invoke(sender, new DownloadCompleteDialogEventArgs
            {
                Path = Path.Combine(label3.Text, label2.Text)
            });
            Dispose();
        }

        private void button3_Click(object sender, EventArgs e)
        {
            FolderOpenClicked?.Invoke(sender, new DownloadCompleteDialogEventArgs
            {
                Path = label3.Text,
                FileName = label2.Text
            });
            Dispose();
        }

        public void ShowDownloadCompleteDialog()
        {
            this.Show();
        }

        private void label1_MouseDown(object sender, MouseEventArgs e)
        {
            label1.DoDragDrop(new DataObject(DataFormats.FileDrop,
                new string[] { Path.Combine(FolderText, FileNameText) }), DragDropEffects.Copy);
        }

        private void button1_Click(object sender, EventArgs e)
        {
            Close();
        }

        private void LoadTexts()
        {
            button1.Text = TextResource.GetText("ND_CANCEL");
            button3.Text = TextResource.GetText("CTX_OPEN_FOLDER");
            button2.Text = TextResource.GetText("CTX_OPEN_FILE");
            Text = TextResource.GetText("CD_TITLE");
        }
    }
}
