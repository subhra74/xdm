using System;
using System.Collections.Generic;
using System.Threading;
using TraceLog;
using XDM.Core;
using XDM.Core.DataAccess;

namespace XDM.Core
{
    public class Scheduler : IDisposable
    {
        private Timer? timer;
        private HashSet<string> activeSchedules;
        //private Action callback;

        public Scheduler()
        {
            this.activeSchedules = new HashSet<string>();
            //this.callback = new Action(() =>
            //{
            //    foreach (var queue in QueueManager.Queues)
            //    {
            //        ProcessScheduledItem(queue);
            //    }
            //});
        }

        public void Start()
        {
            timer = new Timer(OnTimer, null, 0, 60000);
        }

        public void Dispose()
        {
            this.timer?.Dispose();
        }

        public void Stop()
        {
            this.timer?.Dispose();
        }

        private void StartOrStopItem(DownloadQueue item)
        {
            var h1 = DateTime.Now.TimeOfDay.Hours;
            var m1 = DateTime.Now.TimeOfDay.Minutes;
            var h2 = item.Schedule!.Value.EndTime.Hours;
            var m2 = item.Schedule!.Value.EndTime.Minutes;
            var h3 = item.Schedule!.Value.StartTime.Hours;
            var m3 = item.Schedule!.Value.StartTime.Minutes;

            //Log.Debug("DateTime.Now.TimeOfDay: " + DateTime.Now.TimeOfDay
            //    + "\nitem.Schedule!.Value.StartTime: "
            //    + item.Schedule!.Value.StartTime
            //    + "\nitem.Schedule!.Value.EndTime: "
            //    + item.Schedule!.Value.EndTime);

            if (h1 == h2 && m1 == m2)
            {
                ApplicationContext.CoreService.StopDownloads(new List<string>(item.DownloadIds), true);
                this.activeSchedules.Remove(item.ID);
                return;
            }

            if (h1 == h3 && m1 == m3)
            {
                if (this.activeSchedules.Contains(item.ID))
                {
                    return;
                }
                this.activeSchedules.Add(item.ID);
                var dict = new Dictionary<string, DownloadItemBase>();
                foreach (var id in item.DownloadIds)
                {
                    var ent = AppDB.Instance.Downloads.GetDownloadById(id);// ApplicationContext.Core.AppUI.GetInProgressDownloadEntry(id);
                    if (ent != null)
                    {
                        dict[id] = ent;
                    }
                }
                ApplicationContext.CoreService.ResumeDownload(dict, nonInteractive: true);
            }
        }

        private void ProcessScheduledItem(DownloadQueue queue)
        {
            if (queue.Schedule != null)
            {
                //Log.Debug("Queue " + queue + " has schedule: " + queue.Schedule.HasValue);
                var day = queue.Schedule.Value.Days;

                if ((DateTime.Now.DayOfWeek == DayOfWeek.Sunday && HasFlag(day, WeekDays.Sun)) ||
                    (DateTime.Now.DayOfWeek == DayOfWeek.Monday && HasFlag(day, WeekDays.Mon)) ||
                    (DateTime.Now.DayOfWeek == DayOfWeek.Tuesday && HasFlag(day, WeekDays.Tue)) ||
                    (DateTime.Now.DayOfWeek == DayOfWeek.Wednesday && HasFlag(day, WeekDays.Wed)) ||
                    (DateTime.Now.DayOfWeek == DayOfWeek.Thursday && HasFlag(day, WeekDays.Thu)) ||
                    (DateTime.Now.DayOfWeek == DayOfWeek.Friday && HasFlag(day, WeekDays.Fri)) ||
                    (DateTime.Now.DayOfWeek == DayOfWeek.Saturday && HasFlag(day, WeekDays.Sat)))
                {
                    StartOrStopItem(queue);
                }
            }
        }

        private bool HasFlag(WeekDays d1, WeekDays d2)
        {
            return ((byte)d1 & (byte)d2) == (byte)d2;
        }

        private void OnTimer(object? state)
        {
            foreach (var queue in QueueManager.Queues)
            {
                ProcessScheduledItem(queue);
            }
            //ApplicationContext.Core.AppUI.RunOnUiThread(callback);
        }
    }
}
