using System;

using System.Windows.Forms;
using Translations;
using XDM.Core.Lib.Common;

namespace XDM.WinForm.UI
{
    public partial class SchedulerPanel : UserControl
    {
        public event EventHandler? ValueChanged;
        public SchedulerPanel()
        {
            InitializeComponent();

            checkBoxes = new CheckBox[]
            {
                chkSun, chkMon, chkTue, chkWed, chkThu, chkFri,
                chkSat
            };

            bits = new byte[]
            {
                0, 1, 2, 4, 8, 16, 32, 64
            };

            foreach (var chk in checkBoxes)
            {
                chk.CheckedChanged += (a, b) =>
                {
                    if (!chk.Checked)
                    {
                        chkEveryday.Checked = false;
                    }
                    this.ValueChanged?.Invoke(this, EventArgs.Empty);
                };
            }

            dateTimePicker1.ValueChanged += (_, _) => this.ValueChanged?.Invoke(this, EventArgs.Empty);
            dateTimePicker2.ValueChanged += (_, _) => this.ValueChanged?.Invoke(this, EventArgs.Empty);

            LoadTexts();
        }

        private CheckBox[] checkBoxes;
        private byte[] bits;
        public DownloadSchedule Schedule
        {
            get
            {
                return new DownloadSchedule
                {
                    StartTime = dateTimePicker1.Value.TimeOfDay,
                    EndTime = dateTimePicker2.Value.TimeOfDay,
                    Days = GetDaysOfWeek()
                };
            }
            set
            {
                SetDays(value.Days);
                dateTimePicker1.Value = DateTime.Now.Date + value.StartTime;
                dateTimePicker2.Value = DateTime.Now.Date + value.EndTime;
            }
        }

        private WeekDays GetDaysOfWeek()
        {
            var index = 1;
            var weekdays = WeekDays.None;
            foreach (var chk in checkBoxes)
            {
                if (chk.Checked)
                {
                    weekdays |= (WeekDays)bits[index];
                }
                index++;
            }
            return weekdays;
        }

        private void SetDays(WeekDays days)
        {
            var index = 1;
            var allChecked = true;

            foreach (var chk in checkBoxes)
            {
                var day = (WeekDays)bits[index];
                if (((byte)days & (byte)day) == (byte)day)
                {
                    chk.Checked = true;
                }
                else
                {
                    allChecked = false;
                }
                index++;
            }

            chkEveryday.Checked = allChecked;
        }

        private void chkEveryday_CheckedChanged(object sender, EventArgs e)
        {
            if (chkEveryday.Checked)
            {
                foreach (var chk in checkBoxes)
                {
                    chk.Checked = true;
                }
            }
        }

        private void LoadTexts()
        {
            label2.Text = TextResource.GetText("MSG_Q_START");
            label1.Text = TextResource.GetText("MSG_Q_STOP");
            chkEveryday.Text = TextResource.GetText("MSG_Q_DAILY");
            chkSun.Text = TextResource.GetText("MSG_Q_D1");
            chkMon.Text = TextResource.GetText("MSG_Q_D2");
            chkTue.Text = TextResource.GetText("MSG_Q_D3");
            chkWed.Text = TextResource.GetText("MSG_Q_D4");
            chkThu.Text = TextResource.GetText("MSG_Q_D5");
            chkFri.Text = TextResource.GetText("MSG_Q_D6");
            chkSat.Text = TextResource.GetText("MSG_Q_D7");
        }
    }
}
