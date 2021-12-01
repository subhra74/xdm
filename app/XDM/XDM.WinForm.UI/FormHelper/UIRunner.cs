using System;
using System.Windows.Forms;

namespace XDM.WinForm.UI.FormHelper
{
    public static class UIRunner
    {
        public static void RunOnUiThread(Control control, Action callback)
        {
            if (control.InvokeRequired)
            {
                control.BeginInvoke(callback);
            }
            else
            {
                callback.Invoke();
            }
        }

        public static void RunOnUiThread<T>(Control control, Action<T> callback, T value)
        {
            if (control.InvokeRequired)
            {
                control.BeginInvoke(callback, value);
            }
            else
            {
                callback.Invoke(value);
            }
        }
    }
}
