using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Drawing.Text;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace XDM.WinForms.IntegrationUI
{
    public partial class Form1 : Form
    {
        private PrivateFontCollection fc;
        private string folderPath;
        private int index;
        private string browser;
        public Form1()
        {
            InitializeComponent();
            fc = new PrivateFontCollection();
            fc.AddFontFile(Path.Combine(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Font"),
                "fontawesome-webfont.ttf"));
            FontFamily family = fc.Families[0];
            label2.Font = new Font(family, 48);
            label2.Text = "\uf12e";
            label2.MouseDown += Label2_MouseDown;
            folderPath = Path.Combine(
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".xdm-app-data"),
                "chrome-extension");
            browser = "Chrome";
            if (Environment.GetCommandLineArgs().Length == 2)
            {
                browser = Environment.GetCommandLineArgs()[1];
            }
            TopMost = true;
        }

        private void UpdateControls()
        {
            if (index == 0)
            {
                button3.Enabled = false;
                button2.Text = "Next";
                button2.Enabled = true;
                label4.Visible = true;
                textBox2.Visible = button4.Visible = true;
                label1.Visible = label5.Visible = panel1.Visible = label3.Visible = textBox1.Visible = button1.Visible = false;
                LoadImage(1);
            }
            if (index == 1)
            {
                button3.Enabled = true;
                button2.Text = "Next";
                button2.Enabled = true;
                label4.Visible = label5.Visible = false;
                textBox2.Visible = button4.Visible = false;
                label1.Visible = panel1.Visible = label3.Visible = textBox1.Visible = button1.Visible = true;
                LoadImage(2);
            }
            if (index == 2)
            {
                button3.Enabled = true;
                button2.Text = "Finish";
                button2.Enabled = true;
                label5.Visible = true;
                label4.Visible = textBox2.Visible = button4.Visible = label1.Visible = panel1.Visible = label3.Visible = textBox1.Visible = button1.Visible = false;
                LoadImage(3);
            }
        }

        private void LoadImage(int page)
        {
            try
            {
                pictureBox1.Image = Image.FromFile(
                        Path.Combine(Path.Combine(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Gif"), browser), $"img-{page}.gif"));
            }
            catch { }
        }

        private void Label2_MouseDown(object sender, MouseEventArgs e)
        {
            DataObject data = new DataObject(DataFormats.FileDrop, new string[] { folderPath });
            label2.DoDragDrop(data, DragDropEffects.Copy);
        }

        private void Form1_Load(object sender, EventArgs e)
        {
            textBox1.Text = Path.Combine(folderPath);
            UpdateControls();
        }

        private void button2_Click(object sender, EventArgs e)
        {
            if (index == 2)
            {
                Environment.Exit(0);
            }
            index++;
            UpdateControls();
        }

        private void button3_Click(object sender, EventArgs e)
        {
            index--;
            UpdateControls();
        }

        private void button4_Click(object sender, EventArgs e)
        {
            Clipboard.SetText(textBox2.Text);
        }

        private void button1_Click(object sender, EventArgs e)
        {
            Clipboard.SetText(textBox1.Text);
        }
    }
}
