using System;

namespace XDM.WinForm.UI
{
    internal static class FontAwesome
    {
        /// <summary>
        /// fa-file-archive-o
        /// </summary>
        public static readonly string ArchiveIcon = "f1c6";

        /// <summary>
        /// fa-file-text-o
        /// </summary>
        public static readonly string DocumentIcon = "f0f6";

        /// <summary>
        /// fa-music
        /// </summary>
        public static readonly string MusicIcon = "f001";

        /// <summary>
        /// fa-film 
        /// </summary>
        public static readonly string VideoIcon = "f008";

        /// <summary>
        /// fa-window-maximize 
        /// </summary>
        public static readonly string AppIcon = "f2d0";

        /// <summary>
        /// fa-file-o 
        /// </summary>
        public static readonly string OtherFileIcon = "f016";

        /// <summary>
        /// fa-calendar-check-o
        /// </summary>
        public static readonly string ScheduledFileIcon = "f274";

        /// <summary>
        /// fa-pause
        /// </summary>
        public static readonly string PausedIcon = "f04c";

        /// <summary>
        /// fa-download
        /// </summary>
        public static readonly string DownloadIcon = "f019";

        /// <summary>
        /// fa-hourglass-half 
        /// </summary>
        public static readonly string WaitingIcon = "f252";

        /// <summary>
        /// fa-calendar 
        /// </summary>
        public static readonly string ScheduleIcon = "f073";

        /// <summary>
        /// 
        /// </summary>
        /// <param name="code"></param>
        /// <returns></returns>
        public static string GetFontIcon(string code) => ((char)Int32.Parse(code, System.Globalization.NumberStyles.HexNumber)).ToString();

    }
}
