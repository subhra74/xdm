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
            TxtAppVersion.Text = AppInfo.APP_VERSION_TEXT;
            TxtAppVersion.StyleContext.AddClass("medium-font");
            TxtCopyright.Text = AppInfo.APP_COPYRIGHT_TEXT;
            TxtWebsite.Label = AppInfo.APP_HOMEPAGE_TEXT;
            TxtWebsite.Uri = Links.HomePageUrl;

            Title = TextResource.GetText("MENU_ABOUT");
            SetDefaultSize(500, 450);
        }

        public static AboutDialog CreateFromGladeFile(Window parent, WindowGroup group)
        {
            return new AboutDialog(GtkHelper.GetBuilder("about-dialog"), parent, group);
        }
    }
}
