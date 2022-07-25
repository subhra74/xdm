using System;
using System.Windows.Controls;
using XDM.Core;

namespace XDM.Wpf.UI.Dialogs.Scheduler
{
    /// <summary>
    /// Interaction logic for SchedulerPanel.xaml
    /// </summary>
    public partial class SchedulerPanel : UserControl
    {
        public event EventHandler? ValueChanged;
        private byte[] bits;
        private readonly CheckBox[] checkboxes;

        public SchedulerPanel()
        {
            InitializeComponent();

            bits = new byte[]
            {
                0, 1, 2, 4, 8, 16, 32, 64
            };

            checkboxes = new CheckBox[] { chkSun, chkMon, chkTue, chkWed, chkThu, chkFri, chkSat };

            foreach (var chk in checkboxes)
            {
                chk.Unchecked += (a, b) =>
                {
                    chkEveryday.IsChecked = false;
                    this.ValueChanged?.Invoke(this, EventArgs.Empty);
                };

                chk.Checked += (a, b) =>
                {
                    this.ValueChanged?.Invoke(this, EventArgs.Empty);
                };
            }

            chkEveryday.Checked += (_, _) =>
            {
                foreach (var chk in checkboxes)
                {
                    chk.IsChecked = true;
                }
            };

            StartTime.ValueChanged += (_, _) => this.ValueChanged?.Invoke(this, EventArgs.Empty);
            EndTime.ValueChanged += (_, _) => this.ValueChanged?.Invoke(this, EventArgs.Empty);
        }

        private void SetDays(WeekDays days)
        {
            var index = 1;
            var allChecked = true;

            foreach (var chk in checkboxes)
            {
                var day = (WeekDays)bits[index];
                if (((byte)days & (byte)day) == (byte)day)
                {
                    chk.IsChecked = true;
                }
                else
                {
                    allChecked = false;
                }
                index++;
            }

            chkEveryday.IsChecked = allChecked;
        }

        private WeekDays GetDaysOfWeek()
        {
            var index = 1;
            var weekdays = WeekDays.None;
            foreach (var chk in checkboxes)
            {
                if (chk.IsChecked.HasValue && chk.IsChecked.Value)
                {
                    weekdays |= (WeekDays)bits[index];
                }
                index++;
            }
            return weekdays;
        }

        public DownloadSchedule Schedule
        {
            get
            {
                return new DownloadSchedule
                {
                    StartTime = StartTime.Time,
                    EndTime = EndTime.Time,
                    Days = GetDaysOfWeek()
                };
            }
            set
            {
                SetDays(value.Days);
                StartTime.Time = value.StartTime;
                EndTime.Time = value.EndTime;
            }
        }
    }
}
