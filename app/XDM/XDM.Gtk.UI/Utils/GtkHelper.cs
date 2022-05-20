using Gtk;
using System;
using System.IO;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace XDM.GtkUI.Utils
{
    internal static class GtkHelper
    {
        public static void ShowMessageBox(Window window, string text, string? title = null)
        {
            using var msgBox = new MessageDialog(window, DialogFlags.Modal, MessageType.Info, ButtonsType.Ok, text);
            msgBox.Title = title ?? window.Title;
            if (window.Group != null)
            {
                window.Group.AddWindow(msgBox);
            }
            msgBox.Run();
            if (window.Group != null)
            {
                window.Group.RemoveWindow(msgBox);
            }
            msgBox.Destroy();
        }

        public static bool ShowConfirmMessageBox(Window window, string text, string? title = null)
        {
            using var msgBox = new MessageDialog(window, DialogFlags.Modal, MessageType.Question, ButtonsType.YesNo, text);
            msgBox.Title = title ?? window.Title;
            if (window.Group != null)
            {
                window.Group.AddWindow(msgBox);
            }
            var ret = msgBox.Run();
            if (window.Group != null)
            {
                window.Group.RemoveWindow(msgBox);
            }
            msgBox.Destroy();
            return ret == (int)ResponseType.Yes;
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

        public static int GetSelectedIndex(TreeView treeView)
        {
            var paths = treeView.Selection.GetSelectedRows();
            if (paths != null && paths.Length > 0)
            {
                return paths[0].Indices[0];
            }
            return -1;
        }

        public static void SetSelectedIndex(TreeView treeView, int index)
        {
            if (!treeView.Model.GetIterFirst(out TreeIter iter))
            {
                return;
            }
            var i = 0;
            do
            {
                if (index == i)
                {
                    treeView.Selection.SelectIter(iter);
                    return;
                }
                i++;
            }
            while (treeView.Model.IterNext(ref iter));
        }

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
            cell.Ellipsize = Pango.EllipsizeMode.End;
            comboBox.PackStart(cell, true);
            comboBox.AddAttribute(cell, "text", 0);
            return cmbStore;
        }

        public static Gdk.Pixbuf LoadSvg(string name, int dimension = 16)
        {
            return new Gdk.Pixbuf(
                Path.Combine(
                    AppDomain.CurrentDomain.BaseDirectory, "svg-icons", $"{name}.svg"), dimension, dimension, true);
        }

        public static string? SelectFolder(Window parent)
        {
            using var fc = new FileChooserDialog("XDM", parent, FileChooserAction.SelectFolder);
            try
            {
                if (parent.Group != null)
                {
                    parent.Group.AddWindow(fc);
                }
                fc.AddButton(Stock.Save, ResponseType.Accept);
                fc.AddButton(Stock.Cancel, ResponseType.Cancel);
                if (fc.Run() == (int)ResponseType.Accept)
                {
                    return fc.Filename;
                }
                return null;
            }
            finally
            {
                if (parent.Group != null)
                {
                    parent.Group.RemoveWindow(fc);
                }
                fc.Destroy();
                fc.Dispose();
            }
        }

        public static void AttachSafeDispose(Window window)
        {
            window.DeleteEvent += (s, _) =>
            {
                try
                {
                    if (s is Window w)
                    {
                        var g = w.Group;
                        if (g != null)
                        {
                            g.RemoveWindow(w);
                        }
                    }
                }
                catch { }
            };

            window.Destroyed += (s, _) =>
            {
                try
                {
                    if (s is Window w)
                    {
                        w.Dispose();
                    }
                }
                catch { }
            };
        }

        public static void ConfigurePasswordField(Entry? entry)
        {
            if (entry == null)
            {
                return;
            }
            entry.Visibility = false;
            entry.InvisibleChar = '*';
            entry.InputPurpose = InputPurpose.Password;
        }
    }
}
