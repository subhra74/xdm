using System;
using System.Drawing;
using System.Drawing.Text;
using System.Windows.Forms;
using Translations;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Util;
using XDMApp;

namespace XDM.WinForm.UI.SettingsPages
{
    public partial class AdvancedSettingsPage : UserControl, ISettingsPage
    {
        private Font ri16Font;
        public AdvancedSettingsPage()
        {
            InitializeComponent();
            AutoScrollMinSize = tableLayoutPanel1.Size;
            this.ri16Font = new Font(GlobalFontCollection.RiFontInstance.Families[0], 12);
            button1.Font = this.ri16Font;
            button1.Text = RemixIcon.GetFontIcon("ed70");

            LoadTexts();
        }

        public void PopulateUI()
        {
            checkBox1.Checked = Config.Instance.ShutdownAfterAllFinished;
            checkBox2.Checked = Config.Instance.KeepPCAwake;
            checkBox3.Checked = Config.Instance.RunCommandAfterCompletion;
            checkBox4.Checked = Config.Instance.ScanWithAntiVirus;
            checkBox5.Checked = Helpers.IsAutoStartEnabled();//Config.Instance.RunOnLogon;

            textBox1.Text = Config.Instance.AfterCompletionCommand;
            textBox2.Text = Config.Instance.AntiVirusExecutable;
            textBox3.Text = Config.Instance.AntiVirusArgs;
        }

        public void UpdateConfig()
        {
            Config.Instance.ShutdownAfterAllFinished = checkBox1.Checked;
            Config.Instance.KeepPCAwake = checkBox2.Checked;
            Config.Instance.RunCommandAfterCompletion = checkBox3.Checked;
            Config.Instance.ScanWithAntiVirus = checkBox4.Checked;
            Helpers.EnableAutoStart(checkBox5.Checked);
            //Config.Instance.RunOnLogon = checkBox5.Checked;

            Config.Instance.AfterCompletionCommand = textBox1.Text;
            Config.Instance.AntiVirusExecutable = textBox2.Text;
            Config.Instance.AntiVirusArgs = textBox3.Text;
        }

        private void button1_Click(object sender, EventArgs e)
        {
            using var fc = new OpenFileDialog();
            if (fc.ShowDialog(this) == DialogResult.OK)
            {
                textBox2.Text = fc.FileName;
            }
        }

        private void LoadTexts()
        {
            checkBox1.Text = TextResource.GetText("MSG_HALT");
            checkBox2.Text = TextResource.GetText("MSG_AWAKE");
            checkBox3.Text = TextResource.GetText("EXEC_CMD");
            checkBox4.Text = TextResource.GetText("EXE_ANTI_VIR");
            checkBox5.Text = TextResource.GetText("AUTO_START");
            label2.Text = TextResource.GetText("ANTIVIR_CMD");
            label1.Text = TextResource.GetText("ANTIVIR_ARGS");
        }
    }
}
