using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

using Gtk;
using System.IO;
using GLib;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;
using XDM.GtkUI.Utils;
using Translations;

namespace XDM.GtkUI.Dialogs.AdvancedDownload
{
    internal class AdvancedDownloadDialog : Dialog
    {
        public AdvancedDownloadDialog(Window parent,WindowGroup group) : base(TextResource.GetText("DESC_ADV_TITLE"), parent, DialogFlags.Modal)
        {
            SetDefaultSize(550, 450);
            SetPosition(WindowPosition.Center);
            group.AddWindow(this);
            TransientFor = parent;
            //Modal = true;
            //this.AddButton(TextResource.GetText("MSG_OK"), ResponseType.Yes);
            //this.AddButton(TextResource.GetText("ND_CANCEL"), ResponseType.No);

            var builder = new Gtk.Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "advanced-download-window.glade"));
            var tempWindow = (Window)builder.GetObject("window");
            var mainBox = (Widget)builder.GetObject("main-box");
            tempWindow.Remove(mainBox);
            mainBox.ShowAll();
            this.Add(mainBox);
            //this.ContentArea.Expand = true;
            //this.ContentArea.PackStart(mainBox, true, true, 0);

            var btnOk = (Button)builder.GetObject("btn-ok");
            var btnCancel = (Button)builder.GetObject("btn-cancel");
            //btnOk.Clicked += (_, _) =>
            //{
            //    OnResponse(ResponseType.Ok); this.Destroy();
            //};
            //btnCancel.Clicked += (_, _) =>
            //{
            //    OnResponse(ResponseType.Cancel); this.Destroy();
            //};
        }
    }
}
