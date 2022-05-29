using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Gtk;
using GLib;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Core.Lib.Common;
using Translations;
using UI = Gtk.Builder.ObjectAttribute;
using XDM.GtkUI.Utils;

namespace XDM.GtkUI.Dialogs.Properties
{
    internal class PropertiesDialog : Dialog
    {
        [UI] private Label Label1;
        [UI] private Label Label2;
        [UI] private Label Label3;
        [UI] private Label Label4;
        [UI] private Label Label5;
        [UI] private Label Label6;
        [UI] private Label Label7;
        [UI] private Label Label8;
        [UI] private Label Label9;
        [UI] private Label TxtSize;
        [UI] private Label TxtDate;
        [UI] private Label TxtType;
        [UI] private Entry TxtName;
        [UI] private Entry TxtSaveIn;
        [UI] private Entry TxtAddress;
        [UI] private Entry TxtReferer;
        [UI] private Entry TxtCookie;
        [UI] private TextView TxtHeaders;

        private PropertiesDialog(Builder builder, Window parent, WindowGroup group) : base(builder.GetRawOwnedObject("dialog"))
        {
            builder.Autoconnect(this);
            Modal = true;
            SetPosition(WindowPosition.Center);
            TransientFor = parent;
            group.AddWindow(this);
            GtkHelper.AttachSafeDispose(this);
            Title = TextResource.GetText("MENU_PROPERTIES");
            SetDefaultSize(400, 200);
            LoadTexts();
        }

        public string FileName { set => TxtName.Text = value; }
        public string Folder { set => TxtSaveIn.Text = value; }
        public string Address { set => TxtAddress.Text = value; }
        public string FileSize { set => TxtSize.Text = value; }
        public string DateAdded { set => TxtDate.Text = value; }
        public string DownloadType { set => TxtType.Text = value; }
        public string Referer { set => TxtReferer.Text = value; }

        public Dictionary<string, string> Cookies
        {
            set
            {
                if (value != null)
                {
                    var list = new List<string>(value.Values);
                    TxtCookie.Text = string.Join(";", list.ToArray());
                }
            }
        }

        public Dictionary<string, List<string>> Headers
        {
            set
            {
                if (value != null)
                {
                    var textBuf = new StringBuilder();
                    foreach (var key in value.Keys)
                    {
                        foreach (var val in value[key])
                        {
                            textBuf.Append(key + ": " + val + "\r\n");
                        }
                    }
                    TxtHeaders.Buffer.Text = textBuf.ToString();
                }
            }
        }

        private void LoadTexts()
        {
            Label1.Text = TextResource.GetText("SORT_NAME");
            Label2.Text = TextResource.GetText("LBL_SAVE_IN");
            Label3.Text = TextResource.GetText("ND_ADDRESS");
            Label4.Text = TextResource.GetText("SORT_SIZE");
            Label5.Text = TextResource.GetText("SORT_DATE");
            Label6.Text = TextResource.GetText("SORT_TYPE");
            Label7.Text = TextResource.GetText("PROP_REFERER");
            Label8.Text = TextResource.GetText("PROP_COOKIE");
            Label9.Text = TextResource.GetText("MSG_HEADERS");
        }

        public static PropertiesDialog CreateFromGladeFile(Window parent, WindowGroup group)
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "properties-dialog.glade"));
            return new PropertiesDialog(builder, parent, group);
        }
    }
}
