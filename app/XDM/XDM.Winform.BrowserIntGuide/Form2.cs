using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace XDM.Winform.BrowserIntGuide
{
    public partial class Form2 : Form
    {
        public Form2()
        {
            InitializeComponent();
            button1.MouseDown += Button1_MouseDown;
            FormClosed += Form2_FormClosed;
        }

        private void Form2_FormClosed(object sender, FormClosedEventArgs e)
        {
            Environment.Exit(0);
        }

        private void Button1_MouseDown(object sender, MouseEventArgs e)
        {
            button1.DoDragDrop(
                new DataObject(DataFormats.FileDrop, new string[] { @"C:\Program Files (x86)\XDM\chrome-extension" })
                , DragDropEffects.Copy | DragDropEffects.Move);
        }
    }
}
