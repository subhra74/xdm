using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Gtk;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Core;
using Translations;
using UI = Gtk.Builder.ObjectAttribute;
using XDM.GtkUI.Utils;
using System.IO;

namespace XDM.GtkUI.Dialogs.About
{
    public class AboutDialog : Dialog
    {
        [UI] private Label TxtAppVersion, TxtCopyright;
        [UI] private LinkButton TxtWebsite;
        [UI] private Image AppLogo;

        public bool Result { get; set; } = false;

        private WindowGroup group;

        private AboutDialog(Builder builder, Window parent, WindowGroup group) : base(builder.GetRawOwnedObject("dialog"))
        {
            builder.Autoconnect(this);

            Modal = true;
            SetPosition(WindowPosition.CenterAlways);
            TransientFor = parent;
            this.group = group;
            this.group.AddWindow(this);

            GtkHelper.AttachSafeDispose(this);

            AppLogo.Pixbuf = GtkHelper.LoadSvg("xdm-logo", 128);
            TxtAppVersion.Text = "Xtreme Download Manager 8.0.1 BETA";
            TxtAppVersion.StyleContext.AddClass("medium-font");
            TxtCopyright.Text = "© 2013 Subhra Das Gupta";
            TxtWebsite.Label = "www.xtremedownloadmanager.com";
            TxtWebsite.Uri = "https://xtremedownloadmanager.com/";

            Title = TextResource.GetText("MENU_ABOUT");
            SetDefaultSize(500, 450);
        }

        public static AboutDialog CreateFromGladeFile(Window parent, WindowGroup group)
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "about-dialog.glade"));
            return new AboutDialog(builder, parent, group);
        }
    }
}
