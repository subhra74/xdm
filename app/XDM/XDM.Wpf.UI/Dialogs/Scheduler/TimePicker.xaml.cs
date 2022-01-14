using System;
using System.Collections.Generic;
using System.Windows.Controls;
using TraceLog;

namespace XDM.Wpf.UI.Dialogs.Scheduler
{
    /// <summary>
    /// Interaction logic for TimePicker.xaml
    /// </summary>
    public partial class TimePicker : UserControl
    {
        public event EventHandler? ValueChanged;
        private bool suppressEvent = false;
        public TimePicker()
        {
            InitializeComponent();

            var hr = new List<int>(12);
            var mi = new List<int>(60);

            for (int i = 0; i < 12; i++)
            {
                hr.Add(i + 1);
            }

            for (int i = 0; i < 60; i++)
            {
                mi.Add(i);
            }

            CmbHour.ItemsSource = hr;
            CmbMinute.ItemsSource = mi;

            CmbHour.SelectedItem = 11;
            CmbMinute.SelectedItem = 5;

            CmbAmPm.SelectedIndex = 0;

            CmbHour.SelectionChanged += (_, _) =>
            {
                if (!suppressEvent)
                {
                    this.ValueChanged?.Invoke(this, EventArgs.Empty);
                }
            };

            CmbMinute.SelectionChanged += (_, _) =>
            {
                if (!suppressEvent)
                {
                    this.ValueChanged?.Invoke(this, EventArgs.Empty);
                }
            };

            CmbAmPm.SelectionChanged += (_, _) =>
            {
                if (!suppressEvent)
                {
                    this.ValueChanged?.Invoke(this, EventArgs.Empty);
                }
            };
        }

        public TimeSpan Time
        {
            get
            {
                var hour = (int)CmbHour.SelectedItem;
                if (CmbAmPm.SelectedIndex == 0 && hour == 12)
                {
                    hour = 0;
                }
                else if (CmbAmPm.SelectedIndex == 1)
                {
                    hour += 12;
                }
                return new TimeSpan(hour, (int)CmbMinute.SelectedItem, 0);
            }
            set
            {
                Log.Debug("setting time to: " + value);
                suppressEvent = true;
                var hour = value.Hours;
                if (hour < 1)
                {
                    CmbAmPm.SelectedIndex = 0;
                    CmbHour.SelectedItem = 12;
                }
                else if (hour < 12)
                {
                    CmbAmPm.SelectedIndex = 0;
                    CmbHour.SelectedItem = hour;
                }
                else if (hour == 12)
                {
                    CmbAmPm.SelectedIndex = 1;
                    CmbHour.SelectedItem = 12;
                }
                else if (hour > 12)
                {
                    CmbAmPm.SelectedIndex = 1;
                    CmbHour.SelectedItem = hour - 12;
                }
                CmbMinute.SelectedItem = value.Minutes;
                suppressEvent = false;
                Log.Debug("CmbHour.SelectedItem: " + CmbHour.SelectedItem);
            }
        }
    }
}
