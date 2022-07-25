using System;

namespace XDM.Core.Util
{
    public static class TimeHelper
    {
        public static void ConvertH24ToH12(TimeSpan time, out int hrs, out int min, out bool am)
        {
            var hour = time.Hours;
            if (hour < 12)
            {
                am = true;
                hrs = hour == 0 ? 12 : hour;
            }
            else
            {
                am = false;
                hrs = hour > 12 ? hour - 12 : hour;
            }
            min = time.Minutes;
        }

        public static TimeSpan ConvertH12ToH24(int hrs, int min, bool am)
        {
            var hour = 0;
            if (am)
            {
                hour = hrs == 12 ? 0 : hrs;
            }
            else
            {
                hour = hrs < 12 ? hrs + 12 : hrs;
            }
            return new TimeSpan(hour, min, 0);
        }
    }
}
