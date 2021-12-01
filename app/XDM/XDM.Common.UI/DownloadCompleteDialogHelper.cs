using XDM.Core.Lib.Common;
using XDM.Core.Lib.Util;

namespace XDM.Common.UI
{
    public static class DownloadCompleteDialogHelper
    {
        public static void ShowDialog(IApp app, IDownloadCompleteDialog dwnCmpldDlg, string file, string folder)
        {
            dwnCmpldDlg.FileNameText = file;
            dwnCmpldDlg.FolderText = folder;
            dwnCmpldDlg.FileOpenClicked += (sender, args) =>
            {
                Helpers.OpenFile(args.Path);
            };
            dwnCmpldDlg.FolderOpenClicked += (sender, args) =>
            {
                Helpers.OpenFolder(args.Path, args.FileName);
            };
            dwnCmpldDlg.ShowDownloadCompleteDialog();
        }
    }
}
