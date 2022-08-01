using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Gtk;
using TraceLog;

namespace XDM.GtkUI.Utils
{
    internal class ClipboardFileCopy
    {
        private string source;

        public ClipboardFileCopy(string file)
        {
            this.source = $"file://{file}";
        }

        public void Exec()
        {
            var cb = Clipboard.Get(Gdk.Selection.Clipboard);
            if (cb == null)
            {
                Log.Debug("Clipboard is null");
                return;
            }
            var target0 = new TargetEntry("x-special/gnome-copied-files", 0, 0);
            var target1 = new TargetEntry("text/uri-list", 0, 0);
            cb.SetWithData(new TargetEntry[] { target0, target1 }, ClearGet, ClearFunc);
        }

        private void ClearGet(Clipboard clipboard, SelectionData selection, uint info)
        {
            var temp = $"copy\n{source}";
            selection.Set(selection.Target, 8, Encoding.UTF8.GetBytes(temp));
        }

        private void ClearFunc(Clipboard clipboard)
        {
            //???
        }
    }
}
