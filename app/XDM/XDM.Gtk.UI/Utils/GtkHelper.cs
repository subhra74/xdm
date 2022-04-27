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
            if (window.Group != null)
            {
                window.Group.AddWindow(msgBox);
            }
            msgBox.Run();
            msgBox.Destroy();
        }

        public static T GetComboBoxSelectedItem<T>(ComboBox comboBox)
        {
            comboBox.GetActiveIter(out TreeIter tree);
            return (T)comboBox.Model.GetValue(tree, 0);
        }

        //public static int GetSelectedIndex(ComboBox comboBox)
        //{
        //    comboBox.GetActiveIter(out TreeIter tree);
        //    var path = comboBox.Model.GetPath(tree);
        //    return path?.Indices?.Length > 0 ? path.Indices[0] : -1;
        //}

        //public static void SetSelectedIndex(ComboBox comboBox, int index)
        //{
        //    if (!comboBox.Model.GetIterFirst(out TreeIter iter))
        //    {
        //        return;
        //    }
        //    var i = 0;
        //    do
        //    {
        //        if (index == i)
        //        {
        //            comboBox.SetActiveIter(iter);
        //            return;
        //        }
        //        i++;
        //    }
        //    while (comboBox.Model.IterNext(ref iter));
        //}

        public static ListStore PopulateComboBox(ComboBox comboBox, params string[] values)
        {
            var cmbStore = new ListStore(typeof(string));
            foreach (var text in values)
            {
                var iter = cmbStore.Append();
                cmbStore.SetValue(iter, 0, text);
            }
            comboBox.Model = cmbStore;
            var cell = new CellRendererText();
            comboBox.PackStart(cell, true);
            comboBox.AddAttribute(cell, "text", 0);
            return cmbStore;
        }
    }
}
