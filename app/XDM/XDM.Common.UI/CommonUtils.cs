using System.IO;
using TraceLog;
using Translations;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;

namespace XDM.Common.UI
{
    internal static class CommonUtils
    {
        private static string AutoSelectText = TextResource.GetText("ND_AUTO_CAT");
        private static string BrowseText = TextResource.GetText("BTN_BROWSE");
        //internal static void ProcessManualSelection(string selectedFile, IFileSelectable window, ref string? selectedFolder)
        //{
        //    var file = Path.GetFileName(selectedFile);
        //    var folder = Path.GetDirectoryName(selectedFile);

        //    window.SelectedFileName = file;
        //    if (!Config.Instance.RecentFolders.Contains(folder!))
        //    {
        //        Config.Instance.RecentFolders.Insert(0, folder!);
        //        selectedFolder = folder!;
        //    }

        //    Config.Instance.FolderSelectionMode = FolderSelectionMode.Manual;
        //    Config.SaveConfig();
        //    window.FolderSelectionMode = FolderSelectionMode.Manual;
        //}

        internal static string[] GetFolderValues()
        {
            if (!Config.Instance.RecentFolders.Contains(Config.Instance.DefaultDownloadFolder))
            {
                Config.Instance.RecentFolders.Insert(0, Config.Instance.DefaultDownloadFolder);
            }
            var arr = new string[Config.Instance.RecentFolders.Count + 2];
            arr[0] = AutoSelectText;
            arr[1] = BrowseText;
            var k = 2;
            for (var i = 0; i < Config.Instance.RecentFolders.Count; i++, k++)
            {
                arr[k] = Config.Instance.RecentFolders[i];
            }
            return arr;
        }

        internal static void OnFileBrowsed(object? sender, FileBrowsedEventArgs args)
        {
            var file = Path.GetFileName(args.SelectedFile);
            if (string.IsNullOrEmpty(file))
            {
                return;
            }
            var folder = args.SelectedFile;// Path.GetDirectoryName(args.SelectedFile)!;
            if (!Config.Instance.RecentFolders.Contains(folder))
            {
                Config.Instance.RecentFolders.Insert(0, folder);
            }
            if (sender != null)
            {
                var fileSelectable = (IFileSelectable)sender;
                fileSelectable.SelectedFileName = file;
                fileSelectable.SetFolderValues(GetFolderValues());
                fileSelectable.SeletedFolderIndex = 2;
            }
            Config.Instance.FolderSelectionMode = FolderSelectionMode.Manual;
            Config.SaveConfig();
        }

        internal static void OnDropdownSelectionChanged(object? sender, FileBrowsedEventArgs args)
        {
            if (sender != null)
            {
                var fileSelectable = (IFileSelectable)sender;
                var index = fileSelectable.SeletedFolderIndex;
                if (index == 0)
                {
                    Config.Instance.FolderSelectionMode = FolderSelectionMode.Auto;
                }
                else if(!string.IsNullOrEmpty(args.SelectedFile))
                {
                    Config.Instance.FolderSelectionMode = FolderSelectionMode.Manual;
                    if (index > 1)
                    {
                        Config.Instance.RecentFolders.Remove(args.SelectedFile);
                        Config.Instance.RecentFolders.Insert(0, args.SelectedFile);
                    }
                }
                Config.SaveConfig();
            }
        }

        internal static string? SelectedFolderFromIndex(int index)
        {
            if (Config.Instance.FolderSelectionMode == FolderSelectionMode.Auto) return null;
            if (index == 0 || index == 1)
            {
                Log.Debug($"Index value {index} is invalid for {Config.Instance.FolderSelectionMode}");
                return null;
            }
            if (index - 2 < Config.Instance.RecentFolders.Count)
            {
                return Config.Instance.RecentFolders[index - 2];
            }
            return Config.Instance.DefaultDownloadFolder;
        }
    }
}
