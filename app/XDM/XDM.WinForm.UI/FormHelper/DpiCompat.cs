using System.Windows.Forms;

namespace XDM.WinForm.UI.FormHelper
{
    internal static class DpiCompat
    {
        public static int ToDeviceUnits(Control control, int value)
        {
#if !(NET47_OR_GREATER || NET)
            return WinFormsPolyfill.LogicalToDeviceUnits(value);
#else
            return control.LogicalToDeviceUnits(value);
#endif
        }
    }
}
