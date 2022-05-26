using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

using Gtk;
using System.IO;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;
using XDM.GtkUI.Utils;
using Translations;
using UI = Gtk.Builder.ObjectAttribute;
using XDM.Core.Lib.Util;

namespace XDM.GtkUI.Dialogs.QueueScheduler
{
    internal class TimePickerControl
    {
        public event EventHandler? ValueChanged;
        private bool suppressEvent = false;
        private ComboBox cmbHrs, cmbMin, cmbAmPm;
        private Label label;

        public bool Enabled
        {
            set
            {
                cmbHrs.Sensitive = cmbMin.Sensitive = cmbAmPm.Sensitive = label.Sensitive = value;
            }
        }

        public TimePickerControl(ComboBox cmbHrs, ComboBox cmbMin, ComboBox cmbAmPm, Label label)
        {
            this.cmbHrs = cmbHrs;
            this.cmbMin = cmbMin;
            this.cmbAmPm = cmbAmPm;
            this.label = label;

            GtkHelper.PopulateComboBox(this.cmbHrs, Enumerable.Range(1, 12).Select(x => $"{x}").ToArray());
            GtkHelper.PopulateComboBox(this.cmbMin, Enumerable.Range(0, 60).Select(x => x.ToString("D2")).ToArray());
            GtkHelper.PopulateComboBox(this.cmbAmPm, "AM", "PM");

            this.cmbHrs.Active = 10;
            this.cmbMin.Active = 5;
            this.cmbAmPm.Active = 0;

            this.cmbHrs.Changed += (_, _) =>
            {
                if (!suppressEvent)
                {
                    this.ValueChanged?.Invoke(this, EventArgs.Empty);
                }
            };

            this.cmbMin.Changed += (_, _) =>
            {
                if (!suppressEvent)
                {
                    this.ValueChanged?.Invoke(this, EventArgs.Empty);
                }
            };

            this.cmbAmPm.Changed += (_, _) =>
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
                var hrs = cmbHrs.Active + 1;
                var min = cmbMin.Active;
                return TimeHelper.ConvertH12ToH24(hrs,
                       min, cmbAmPm.Active == 0);
            }
            set
            {
                suppressEvent = true;
                TimeHelper.ConvertH24ToH12(value, out int hh, out int mi, out bool am);
                cmbAmPm.Active = am ? 0 : 1;
                cmbHrs.Active = hh - 1;
                cmbMin.Active = mi;
                suppressEvent = false;
            }
        }
    }
}
