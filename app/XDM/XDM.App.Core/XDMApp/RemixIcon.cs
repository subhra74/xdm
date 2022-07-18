using System;

namespace XDMApp
{
    public static class RemixIcon
    {
        /// <summary>
        /// ri-link
        /// </summary>
        public static readonly string LinkIcon = "eeb2";

        /// <summary>
        /// ri-delete-bin-7-line
        /// </summary>
        public static readonly string RemoveIcon = "ec28";

        /// <summary>
        /// ri-play-line
        /// </summary>
        public static readonly string ResumeIcon = "f00b";

        /// <summary>
        /// ri-pause-line
        /// </summary>
        public static readonly string PauseIcon = "efd8";

        /// <summary>
        /// ri-search-line
        /// </summary>
        public static readonly string SearchIcon = "f0d1";

        /// <summary>
        /// ri-menu-line
        /// </summary>
        public static readonly string MenuIcon = "ef3e";

        /// <summary>
        /// ri-arrow-down-s-line
        /// </summary>
        public static readonly string DownArrowIcon = "ea4e";

        /// <summary>
        /// ri-external-link-line
        /// </summary>
        public static readonly string FileOpenIcon = "ecaf";

        /// <summary>
        /// ri-folder-shared-line
        /// </summary>
        public static readonly string FolderOpenIcon = "ed78";

        /// <summary>
        /// ri-pause-circle-line
        /// </summary>
        public static readonly string DownloadPausedIcon = "efda";

        /// <summary>
        /// ri-arrow-down-circle-line
        /// </summary>
        public static readonly string DownloadActiveIcon = "ea4a";

        /// <summary>
        /// ri-file-line
        /// </summary>
        public static readonly string FileIcon = "eceb";

        /// <summary>
        /// ri-wifi-line
        /// </summary>
        public static readonly string WifiOnIcon = "f2c0";

        /// <summary>
        /// ri-wifi-off-line
        /// </summary>
        public static readonly string WifiOffIcon = "f2c2";

        /// <summary>
        /// ri-question-line
        /// </summary>
        public static readonly string HelpIcon = "f045";

        /// <summary>
        /// ri-list-settings-line
        /// </summary>
        public static readonly string SettingsIcon = "eebd";

        /// <summary>
        /// ri-toggle-fill
        /// </summary>
        public static readonly string ToggleOnIcon = "f218";

        /// <summary>
        /// ri-toggle-line
        /// </summary>
        public static readonly string ToggleOffIcon = "f219";

        /// <summary>
        /// ri-file-zip-fill
        /// </summary>
        public static readonly string ArchiveIcon = "ed1e";

        /// <summary>
        /// ri-file-text-fill
        /// </summary>
        public static readonly string DocumentIcon = "ed0e";

        /// <summary>
        /// ri-file-music-fill
        /// </summary>
        public static readonly string MusicIcon = "ecf6";

        /// <summary>
        /// ri-clapperboard-fill
        /// </summary>
        public static readonly string VideoIcon = "ef80";

        /// <summary>
        /// ri-function-fill
        /// </summary>
        public static readonly string AppIcon = "ed9d";

        /// <summary>
        /// ri-file-fill
        /// </summary>
        public static readonly string OtherFileIcon = "ece0";

        /// <summary>
        /// ri-alarm-fill
        /// </summary>
        public static readonly string ScheduledFileIcon = "ea1a";

        /// <summary>
        /// ri-file-zip-fill
        /// </summary>
        public static readonly string ArchiveIconLine = "ed1f";

        /// <summary>
        /// ri-file-text-fill
        /// </summary>
        public static readonly string DocumentIconLine = "ed0f";

        /// <summary>
        /// ri-file-music-fill
        /// </summary>
        public static readonly string MusicIconLine = "ecf7";

        /// <summary>
        /// ri-movie-line
        /// </summary>
        public static readonly string VideoIconLine = "ef81";

        /// <summary>
        /// ri-function-fill
        /// </summary>
        public static readonly string AppIconLine = "ed9e";

        /// <summary>
        /// ri-file-line
        /// </summary>
        public static readonly string OtherFileIconLine = "eceb";

        /// <summary>
        /// ri-alarm-fill
        /// </summary>
        public static readonly string ScheduledFileIconLine = "ea1b";

        /// <summary>
        /// ri-refresh-fill
        /// </summary>
        public static readonly string NotificationIcon = "f063";

        /// <summary>
        /// 
        /// </summary>
        /// <param name="code"></param>
        /// <returns></returns>
        public static string GetFontIcon(string code) => ((char)Int32.Parse(code, System.Globalization.NumberStyles.HexNumber)).ToString();
    }
}
