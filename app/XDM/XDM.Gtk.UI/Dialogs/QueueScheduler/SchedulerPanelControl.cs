using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

using Gtk;
using System.IO;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Core;
using XDM.Core.UI;
using XDM.GtkUI.Utils;
using Translations;
using UI = Gtk.Builder.ObjectAttribute;
using XDM.Core.Util;

namespace XDM.GtkUI.Dialogs.QueueScheduler
{
    internal class SchedulerPanelControl
    {
        private CheckButton chkEveryday = null;

        public event EventHandler? ValueChanged;
        private byte[] bits;
        private readonly CheckButton[] checkboxes;
        private TimePickerControl StartTime, EndTime;
        private bool suppressEvents = false;

        public bool Enabled
        {
            set
            {
                chkEveryday.Sensitive = StartTime.Enabled = EndTime.Enabled = value;
                foreach (var chk in checkboxes)
                {
                    chk.Sensitive = value;
                }
            }
        }

        public SchedulerPanelControl(CheckButton chkEveryday,
            CheckButton[] checkboxes,
            TimePickerControl startTime,
            TimePickerControl endTime)
        {
            this.chkEveryday = chkEveryday;
            this.checkboxes = checkboxes;
            this.StartTime = startTime;
            this.EndTime = endTime;

            bits = new byte[]
            {
                0, 1, 2, 4, 8, 16, 32, 64
            };

            foreach (var chk in checkboxes)
            {
                chk.Toggled += (_, _) =>
                {
                    if (!chk.Active)
                    {
                        chkEveryday.Active = false;
                    }
                    EmitValueChanged();
                };
            }

            chkEveryday.Toggled += (_, _) =>
            {
                if (chkEveryday.Active)
                {
                    foreach (var chk in checkboxes)
                    {
                        chk.Active = true;
                    }
                }
            };

            startTime.ValueChanged += (_, _) => EmitValueChanged();
            endTime.ValueChanged += (_, _) => EmitValueChanged();
        }

        private void EmitValueChanged()
        {
            if (!suppressEvents)
            {
                this.ValueChanged?.Invoke(this, EventArgs.Empty);
            }
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
                    chk.Active = true;
                }
                else
                {
                    allChecked = false;
                }
                index++;
            }

            chkEveryday.Active = allChecked;
        }

        private WeekDays GetDaysOfWeek()
        {
            var index = 1;
            var weekdays = WeekDays.None;
            foreach (var chk in checkboxes)
            {
                if (chk.Active)
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
                suppressEvents = true;
                SetDays(value.Days);
                StartTime.Time = value.StartTime;
                EndTime.Time = value.EndTime;
                suppressEvents = false;
            }
        }
    }
}
