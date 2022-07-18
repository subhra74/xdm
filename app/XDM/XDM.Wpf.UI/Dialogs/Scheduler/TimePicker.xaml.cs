using System;
using System.Collections.Generic;
using System.Windows.Controls;
using TraceLog;
using XDM.Core.Lib.Util;

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
                return TimeHelper.ConvertH12ToH24((int)CmbHour.SelectedItem,
                    (int)CmbMinute.SelectedItem, CmbAmPm.SelectedIndex == 0);
            }
            set
            {
                suppressEvent = true;
                TimeHelper.ConvertH24ToH12(value, out int hh, out int mi, out bool am);
                CmbAmPm.SelectedIndex = am ? 0 : 1;
                CmbHour.SelectedItem = hh;
                CmbMinute.SelectedItem = mi;
                suppressEvent = false;
            }
        }
    }
}
