using System;
using System.Collections.Generic;
using System.Windows.Controls;

namespace XDM.Wpf.UI.Dialogs.Scheduler
{
    /// <summary>
    /// Interaction logic for TimePicker.xaml
    /// </summary>
    public partial class TimePicker : UserControl
    {
        public event EventHandler? ValueChanged;
        public TimePicker()
        {
            InitializeComponent();

            var hr = new List<int>(12);
            var mi = new List<int>(60);

            for (int i = 0; i < 24; i++)
            {
                hr.Add(i);
            }

            for (int i = 0; i < 60; i++)
            {
                mi.Add(i);
            }

            CmbHour.ItemsSource = hr;
            CmbMinute.ItemsSource = mi;

            CmbHour.SelectedIndex = 11;
            CmbMinute.SelectedIndex = 0;

            CmbAmPm.SelectedIndex = 0;

            CmbHour.SelectionChanged += (_, _) => this.ValueChanged?.Invoke(this, EventArgs.Empty);
            CmbMinute.SelectionChanged += (_, _) => this.ValueChanged?.Invoke(this, EventArgs.Empty);
            CmbAmPm.SelectionChanged += (_, _) => this.ValueChanged?.Invoke(this, EventArgs.Empty);
        }

        public TimeSpan Time
        {
            get
            {
                var hour = (int)CmbHour.SelectedItem;
                if (CmbMinute.SelectedIndex == 1)
                {
                    hour += 12;
                }
                return new TimeSpan(hour, (int)CmbHour.SelectedItem, 0);
            }
            set
            {
                var hour = value.Hours;
                if (hour > 12)
                {
                    CmbAmPm.SelectedIndex = 0;
                }
                else
                {
                    CmbAmPm.SelectedIndex = 1;
                }
                CmbHour.SelectedItem = hour - 12;
                CmbMinute.SelectedItem = value.Minutes;
            }
        }
    }
}
