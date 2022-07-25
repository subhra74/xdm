using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace XDM.Core
{
    public static class NewDownloadPromptTracker
    {
        private static readonly HashSet<string> newDownloadPrompts = new();

        public static bool IsPromptAlreadyOpen(string url)
        {
            lock (newDownloadPrompts)
            {
                return newDownloadPrompts.Contains(url);
            }
        }

        public static void PromptOpen(string url)
        {
            lock (newDownloadPrompts)
            {
                newDownloadPrompts.Add(url);
            }
        }

        public static void PromptClosed(string url)
        {
            lock (newDownloadPrompts)
            {
                newDownloadPrompts.Remove(url);
            }
        }
    }
}
