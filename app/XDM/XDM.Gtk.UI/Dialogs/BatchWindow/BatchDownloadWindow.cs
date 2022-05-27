using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using UI = Gtk.Builder.ObjectAttribute;
using Gtk;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Common.UI;
using Translations;
using XDM.Core.Lib.Common;
using XDM.GtkUI.Utils;

namespace XDM.GtkUI.Dialogs.BatchWindow
{
    internal class BatchDownloadWindow : Window
    {
        private WindowGroup windowGroup;
        public int BatchSize { get; private set; } = 0;
        public IAppUI AppUI { get; set; }
        public IApp App { get; set; }

        [UI] private Label Label1;
        [UI] private Label Label2;
        [UI] private Label Label3;
        [UI] private Label Label4;
        [UI] private Label Label5;
        [UI] private Label Label6;
        [UI] private Label Label7;
        [UI] private Label Label8;
        [UI] private Label PageLabel2;
        [UI] private Label Header1;
        [UI] private Label Header2;
        [UI] private Button BtnOK;
        [UI] private Button BtnCancel;
        [UI] private CheckButton ChkLeadingZero;
        [UI] private ComboBox CmbType;
        [UI] private ComboBox CmbLetterFrom;
        [UI] private ComboBox CmbLetterTo;
        [UI] private Entry TxtAddress;
        [UI] private SpinButton TxtNumberFrom;
        [UI] private SpinButton TxtNumberTo;
        [UI] private Entry TxtFile1;
        [UI] private Entry TxtFile2;
        [UI] private Entry TxtFileN;
        [UI] private Entry TxtLeadingZero;

        private BatchDownloadWindow(Builder builder, Window parent, IApp app, IAppUI appUi) : base(builder.GetRawOwnedObject("window"))
        {
            builder.Autoconnect(this);
            this.App = app;
            this.AppUI = appUi;
            Title = TextResource.GetText("MENU_BATCH_DOWNLOAD");
            SetDefaultSize(600, 500);
            SetPosition(WindowPosition.Center);
            TransientFor = parent;

            this.windowGroup = new WindowGroup();
            this.windowGroup.AddWindow(this);

            GtkHelper.AttachSafeDispose(this);
            LoadTexts();

            GtkHelper.PopulateComboBox(CmbType, TextResource.GetText("LBL_BATCH_LETTER"), TextResource.GetText("LBL_BATCH_NUM"));
            GtkHelper.PopulateComboBox(CmbLetterFrom, "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z");
            GtkHelper.PopulateComboBox(CmbLetterTo, "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z");

            CmbType.Changed += CmbType_Changed;
            CmbType.Active = 0;
        }

        private void CmbType_Changed(object? sender, EventArgs e)
        {
            if (CmbType.Active == 1)
            {
                CmbLetterFrom.Hide();
                CmbLetterTo.Hide();
                TxtNumberFrom.ShowAll();
                TxtNumberTo.ShowAll();
            }
            else
            {
                CmbLetterFrom.ShowAll();
                CmbLetterTo.ShowAll();
                TxtNumberFrom.Hide();
                TxtNumberTo.Hide();
            }
            OnBatchPatternChange();
        }

        private void OnBatchPatternChange() { }

        private void LoadTexts()
        {
            Label1.Text = TextResource.GetText("LBL_BATCH_DESC");
            Label2.Text = TextResource.GetText("ND_ADDRESS");
            Label3.Text = TextResource.GetText("LBL_BATCH_ASTERISK");
            Label4.Text = TextResource.GetText("LBL_BATCH_FROM");
            Label5.Text = TextResource.GetText("LBL_BATCH_TO");
            Label6.Text = TextResource.GetText("LBL_BATCH_FILE1");
            Label7.Text = TextResource.GetText("LBL_BATCH_FILE2");
            Label8.Text = TextResource.GetText("LBL_BATCH_FILEN");
            PageLabel2.Text = TextResource.GetText("BAT_PASTE_LINK");
            Header1.Text = TextResource.GetText("BAT_PATTERN");
            Header2.Text = TextResource.GetText("BAT_LINKS");
            BtnCancel.Label = TextResource.GetText("ND_CANCEL");
            BtnOK.Label = TextResource.GetText("MSG_OK");
            ChkLeadingZero.Label = TextResource.GetText("BAT_LEADING_ZERO");
        }

        public static BatchDownloadWindow CreateFromGladeFile(Window parent, IApp app, IAppUI appUi)
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "batch-download-dialog.glade"));
            return new BatchDownloadWindow(builder, parent, app, appUi);
        }
    }
}
