using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;


namespace XDM.Core
{
    public struct DownloadSchedule
    {
        public TimeSpan StartTime { get; set; }
        public TimeSpan EndTime { get; set; }
        public WeekDays Days { get; set; }
    }

    [Flags]
    public enum WeekDays : byte
    {
        None = 0, Sun = 1, Mon = 2, Tue = 4, Wed = 8, Thu = 16, Fri = 32, Sat = 64
    }
}
