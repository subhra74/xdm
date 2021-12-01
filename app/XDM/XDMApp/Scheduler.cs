using System;
using System.Collections.Generic;
using System.Threading;
using TraceLog;
using XDM.Core.Lib.Common;

namespace XDMApp
{
    public class Scheduler : IDisposable
    {
        private Timer? timer;
        private readonly IApp app;
        private HashSet<string> activeSchedules;
        private Action callback;

        public Scheduler(IApp app)
        {
            this.app = app;
            this.activeSchedules = new HashSet<string>();
            this.callback = new Action(() =>
            {
                foreach (var queue in QueueManager.Queues)
                {
                    ProcessScheduledItem(queue);
                }
            });
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
            if (item.Schedule!.Value.EndTime == DateTime.Now.TimeOfDay)
            {
                app.StopDownloads(new List<string>(item.DownloadIds), true);
                this.activeSchedules.Remove(item.ID);
                return;
            }

            if (item.Schedule.Value.StartTime == DateTime.Now.TimeOfDay)
            {
                if (this.activeSchedules.Contains(item.ID))
                {
                    return;
                }
                this.activeSchedules.Add(item.ID);
                var dict = new Dictionary<string, BaseDownloadEntry>();
                foreach (var id in item.DownloadIds)
                {
                    var ent = app.AppUI.GetInProgressDownloadEntry(id);
                    if (ent != null)
                    {
                        dict[id] = ent;
                    }
                }
                app.ResumeDownload(dict, nonInteractive: true);
            }
        }

        private void ProcessScheduledItem(DownloadQueue queue)
        {
            if (queue.Schedule != null)
            {
                Log.Debug("Queue " + queue + " has schedule: " + queue.Schedule.HasValue);
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
            app.AppUI.RunOnUiThread(callback);
        }
    }
}
