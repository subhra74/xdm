using XDM.Core.Lib.Common;

namespace XDM.Wpf.UI.Dialogs.Settings
{
    internal interface ISettingsPage
    {
        void PopulateUI();
        void UpdateConfig();

        public IApp App { get; set; }
    }
}