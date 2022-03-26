using Gtk;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace XDM.GtkUI.Utils
{
    internal static class GtkHelper
    {
        public static void ShowMessageBox(Window window, string text)
        {
            var msgBox = new MessageDialog(window, DialogFlags.Modal, MessageType.Info, ButtonsType.Ok, text);
            msgBox.Run();
            msgBox.Destroy();
        }

        public static T GetComboBoxSelectedItem<T>(ComboBox comboBox)
        {
            comboBox.GetActiveIter(out TreeIter tree);
            return (T)comboBox.Model.GetValue(tree, 0);
        }
    }
}
