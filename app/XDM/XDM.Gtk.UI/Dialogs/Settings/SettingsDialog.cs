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
using XDMApp;
using XDM.Core.Lib.Util;

namespace XDM.GtkUI.Dialogs.Settings
{
    internal class SettingsDialog : Dialog
    {
        private WindowGroup group;
        [UI]
        private Label TabHeader1, TabHeader2, TabHeader3, TabHeader4, TabHeader5;
        [UI]
        private Label Label1, Label2, Label3, Label4, Label5, Label6, Label7,
            Label8, Label9, Label10, Label11, Label12, Label13, Label14, Label15,
            Label16, Label17, Label18, Label19, Label20, Label21,
            Label22, Label23, Label24, Label25;
        [UI]
        private LinkButton VideoWikiLink;
        [UI]
        Button BtnChrome, BtnFirefox, BtnEdge, BtnOpera, BtnDefault1, BtnDefault2,
            BtnDefault3, CatAdd, CatEdit, CatDel, CatDef;
        [UI]
        private CheckButton ChkMonitorClipboard, ChkTimestamp, ChkDarkTheme, ChkAutoCat, ChkShowPrg,
            ChkShowComplete, ChkStartAuto, ChkOverwrite, ChkEnableSpeedLimit;

        private SettingsDialog(Builder builder,
            Window parent,
            WindowGroup group,
            IAppUI ui,
            IApp app) : base(builder.GetRawOwnedObject("dialog"))
        {
            builder.Autoconnect(this);

            Modal = true;
            SetDefaultSize(640, 480);
            SetPosition(WindowPosition.CenterAlways);
            TransientFor = parent;
            this.group = group;
            this.group.AddWindow(this);
            GtkHelper.AttachSafeDispose(this);
            LoadTexts();
            Title = TextResource.GetText("TITLE_SETTINGS");
        }

        private void LoadTexts()
        {
            TabHeader1.Text = TextResource.GetText("SETTINGS_MONITORING");
            TabHeader2.Text = TextResource.GetText("SETTINGS_GENERAL");
            TabHeader3.Text = TextResource.GetText("SETTINGS_NETWORK");
            TabHeader4.Text = TextResource.GetText("SETTINGS_CRED");
            TabHeader5.Text = TextResource.GetText("SETTINGS_ADV");

            Label1.StyleContext.AddClass("medium-font");

            Label1.Text = TextResource.GetText("SETTINGS_MONITORING");
            Label2.Text = TextResource.GetText("DESC_MONITORING_1");
            Label3.Text = TextResource.GetText("MSG_VID_WIKI_TEXT");
            VideoWikiLink.Label = TextResource.GetText("MSG_VID_WIKI_LINK");

            Label4.Text = TextResource.GetText("DESC_OTHER_BROWSERS");
            Label5.Text = TextResource.GetText("DESC_CHROME");
            Label6.Text = TextResource.GetText("DESC_MOZ");

            //BtnChrome.Label = TextResource.GetText("MSG_VID_WIKI_LINK");
            //BtnFirefox.Label = TextResource.GetText("MSG_VID_WIKI_LINK");
            //BtnEdge.Label = TextResource.GetText("MSG_VID_WIKI_LINK");
            //BtnOpera.Label = TextResource.GetText("MSG_VID_WIKI_LINK");

            Label7.Text = TextResource.GetText("DESC_FILETYPES");
            Label8.Text = TextResource.GetText("DESC_VIDEOTYPES");
            Label9.Text = TextResource.GetText("DESC_SITEEXCEPTIONS");
            Label10.Text = TextResource.GetText("LBL_MIN_VIDEO_SIZE");

            BtnDefault1.Label = BtnDefault2.Label = BtnDefault3.Label = TextResource.GetText("DESC_DEF");

            ChkMonitorClipboard.Label = TextResource.GetText("MENU_CLIP_ADD");
            ChkTimestamp.Label = TextResource.GetText("LBL_GET_TIMESTAMP");

            Label11.StyleContext.AddClass("medium-font");

            Label11.Text = TextResource.GetText("SETTINGS_GENERAL");
            Label12.Text = TextResource.GetText("MSG_DOUBLE_CLICK_ACTION");
            Label13.Text = TextResource.GetText("LBL_TEMP_FOLDER");
            Label14.Text = TextResource.GetText("SETTINGS_FOLDER");
            Label15.Text = TextResource.GetText("MSG_MAX_DOWNLOAD");
            Label16.Text = TextResource.GetText("SETTINGS_CAT");

            ChkDarkTheme.Label = TextResource.GetText("SETTINGS_DARK_THEME");
            ChkAutoCat.Label = TextResource.GetText("SETTINGS_ATUO_CAT");
            ChkShowPrg.Label = TextResource.GetText("SHOW_DWN_PRG");
            ChkShowComplete.Label = TextResource.GetText("SHOW_DWN_COMPLETE");
            ChkStartAuto.Label = TextResource.GetText("LBL_START_AUTO");
            ChkOverwrite.Label = TextResource.GetText("LBL_OVERWRITE_EXISTING");
            CatAdd.Label = TextResource.GetText("SETTINGS_CAT_ADD");
            CatEdit.Label = TextResource.GetText("SETTINGS_CAT_EDIT");
            CatDel.Label = TextResource.GetText("DESC_DEL");
            CatDef.Label = TextResource.GetText("DESC_DEF");

            Label17.StyleContext.AddClass("medium-font");

            Label17.Text = TextResource.GetText("SETTINGS_NETWORK");
            Label18.Text = TextResource.GetText("DESC_NET1");
            Label19.Text = TextResource.GetText("DESC_NET2");
            Label20.Text = TextResource.GetText("NET_MAX_RETRY");
            Label21.Text = TextResource.GetText("DESC_NET4");
            Label22.Text = TextResource.GetText("PROXY_HOST");
            Label23.Text = TextResource.GetText("PROXY_PORT");
            Label24.Text = TextResource.GetText("DESC_NET7");
            Label25.Text = TextResource.GetText("DESC_NET8");

            ChkEnableSpeedLimit.Label = TextResource.GetText("MSG_SPEED_LIMIT");
        }

        public static SettingsDialog CreateFromGladeFile(Window parent, WindowGroup group, IAppUI ui, IApp app)
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "settings-dialog.glade"));
            return new SettingsDialog(builder, parent, group, ui, app);
        }
    }
}
