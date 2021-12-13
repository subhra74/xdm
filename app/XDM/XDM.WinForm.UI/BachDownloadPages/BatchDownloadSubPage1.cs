using TraceLog;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows.Forms;
using Translations;

namespace XDM.WinForm.UI.BachDownloadPages
{
    public partial class BatchDownloadSubPage1 : UserControl
    {
        private bool usingNumbers = true;
        public int BatchSize { get; private set; } = 0;
        public BatchDownloadSubPage1()
        {
            InitializeComponent();
            textBox1.TextChanged += (_, _) =>
            {
                OnBatchPatternChange();
            };
            numericUpDown1.ValueChanged += (_, _) =>
            {
                OnBatchPatternChange();
            };
            numericUpDown2.ValueChanged += (_, _) =>
            {
                OnBatchPatternChange();
            };
            comboBox1.SelectedIndexChanged += (_, _) =>
            {
                OnBatchPatternChange();
            };
            comboBox2.SelectedIndexChanged += (_, _) =>
            {
                OnBatchPatternChange();
            };
            checkBox1.CheckedChanged += (_, _) =>
            {
                OnBatchPatternChange();
            };
            numericUpDown3.ValueChanged += (_, _) =>
            {
                OnBatchPatternChange();
            };
            radioButton1.CheckedChanged += (_, _) =>
            {
                if (radioButton1.Checked)
                {
                    usingNumbers = true;
                    label6.Visible = label7.Visible = comboBox1.Visible = comboBox2.Visible = false;
                    label5.Visible = label4.Visible = numericUpDown1.Visible = numericUpDown2.Visible = true;
                    checkBox1.Visible = numericUpDown3.Visible = true;
                    OnBatchPatternChange();
                }
            };
            radioButton2.CheckedChanged += (_, _) =>
            {
                if (radioButton2.Checked)
                {
                    usingNumbers = false;
                    label6.Visible = label7.Visible = comboBox1.Visible = comboBox2.Visible = true;
                    label5.Visible = label4.Visible = numericUpDown1.Visible = numericUpDown2.Visible = false;
                    checkBox1.Visible = numericUpDown3.Visible = false;
                    OnBatchPatternChange();
                }
            };
            comboBox1.SelectedIndex = 0;
            comboBox2.SelectedIndex = comboBox2.Items.Count - 1;
            LoadTexts();
        }

        private void OnBatchPatternChange()
        {
            try
            {
                textBox4.Text = textBox5.Text = textBox6.Text = string.Empty;
                if (!textBox1.Text.Contains('*')) return;
                var c = 0;
                var last = string.Empty;
                BatchSize = 0;
                foreach (var url in GenerateBatchLink(textBox1.Text))
                {
                    if (c == 0)
                    {
                        textBox4.Text = url.ToString();
                    }
                    else if (c == 1)
                    {
                        textBox5.Text = url.ToString();
                    }
                    last = url.ToString();
                    c++;
                    BatchSize++;
                }
                if (c > 1)
                {
                    textBox6.Text = last;
                }
            }
            catch (UriFormatException)
            {
                MessageBox.Show(this,TextResource.GetText("MSG_INVALID_URL"));
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error");
            }

        }

        public IEnumerable<Uri> GenerateBatchLink()
        {
            if (!textBox1.Text.Contains('*')) return Enumerable.Empty<Uri>();
            try
            {
                return GenerateBatchLink(textBox1.Text);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error generating batch links");
                return Enumerable.Empty<Uri>();
            }
        }

        private IEnumerable<Uri> GenerateBatchLink(string url)
        {
            if (usingNumbers)
            {
                var startNum = (int)numericUpDown1.Value;
                var endNum = (int)numericUpDown2.Value;

                if (startNum >= endNum)
                {
                    throw new ArgumentException();
                }

                for (var i = startNum; i <= endNum; i++)
                {
                    yield return new Uri(url.Replace("*",
                        checkBox1.Checked ? i.ToString($"D{(int)numericUpDown3.Value}") :
                        i.ToString()));
                }
            }
            else
            {
                var startChar = comboBox1.SelectedItem.ToString()[0];
                var endChar = comboBox2.SelectedItem.ToString()[0];

                if (startChar >= endChar)
                {
                    throw new ArgumentException();
                }

                for (var i = startChar; i <= endChar; i++)
                {
                    yield return new Uri(url.Replace('*', i));
                }
            }
        }

        private void LoadTexts()
        {
            label1.Text = TextResource.GetText("LBL_BATCH_DESC");
            label2.Text = TextResource.GetText("ND_ADDRESS");
            label3.Text = TextResource.GetText("LBL_BATCH_ASTERISK");
            radioButton1.Text = TextResource.GetText("LBL_BATCH_NUM");
            radioButton2.Text = TextResource.GetText("LBL_BATCH_LETTER");
            label4.Text = TextResource.GetText("LBL_BATCH_FROM");
            label5.Text = TextResource.GetText("LBL_BATCH_TO");
            label8.Text = TextResource.GetText("LBL_BATCH_FILE1");
            label9.Text = TextResource.GetText("LBL_BATCH_FILE2");
            label10.Text = TextResource.GetText("LBL_BATCH_FILEN");
            checkBox1.Text = TextResource.GetText("BAT_LEADING_ZERO");
        }
    }
}
