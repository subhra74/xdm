using XDM.Core;
using XDM.Core.Util;

namespace XDM.Core.UI
{
    public static class DownloadCompleteDialogHelper
    {
        public static void ShowDialog(IApplicationCore app, IDownloadCompleteDialog dwnCmpldDlg, string file, string folder)
        {
            dwnCmpldDlg.FileNameText = file;
            dwnCmpldDlg.FolderText = folder;
            dwnCmpldDlg.FileOpenClicked += (sender, args) =>
            {
                if (!string.IsNullOrEmpty(args.Path))
                {
                    Helpers.OpenFile(args.Path!);
                }
            };
            dwnCmpldDlg.FolderOpenClicked += (sender, args) =>
            {
                if (!string.IsNullOrEmpty(args.Path))
                {
                    Helpers.OpenFolder(args.Path!, args.FileName);
                }
            };
            dwnCmpldDlg.DontShowAgainClickd += (sender, args) =>
            {
                Config.Instance.ShowDownloadCompleteWindow = false;
            };
            dwnCmpldDlg.ShowDownloadCompleteDialog();
        }
    }
}
