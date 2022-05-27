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
using XDM.Core.Lib.UI;
using XDM.Core.Lib.Util;

namespace XDM.GtkUI.Dialogs.BatchWindow
{
    internal class BatchDownloadWindow : Window, IBatchDownloadView
    {
        public bool IsLetterMode { get => CmbType.Active == 0; set => CmbType.Active = value ? 0 : 1; }
        public bool IsUsingLeadingZero { get => ChkLeadingZero.Active; set => ChkLeadingZero.Active = value; }
        public string Url { get => TxtAddress.Text; set => TxtAddress.Text = value; }
        public char? StartLetter => CmbLetterFrom.Active < 0 ? null : GtkHelper.GetComboBoxSelectedItem<string>(CmbLetterFrom)[0];
        public char? EndLetter => CmbLetterTo.Active < 0 ? null : GtkHelper.GetComboBoxSelectedItem<string>(CmbLetterTo)[0];
        public int StartNumber => Helpers.ParseIntSafe(TxtNumberFrom.Text);
        public int EndNumber => Helpers.ParseIntSafe(TxtNumberTo.Text);
        public int LeadingZeroCount => Helpers.ParseIntSafe(TxtLeadingZero.Text);
        public string BatchAddress1 { get => TxtFile1.Text; set => TxtFile1.Text = value; }
        public string BatchAddress2 { get => TxtFile2.Text; set => TxtFile2.Text = value; }
        public string BatchAddressN { get => TxtFileN.Text; set => TxtFileN.Text = value; }
        public bool IsBatchMode => this.TabControl.CurrentPage == 0;

        public event EventHandler? PatternChanged;
        public event EventHandler? OkClicked;

        public void SetStartLetterRange(string[] range)
        {
            GtkHelper.PopulateComboBox(this.CmbLetterFrom, range);
            //this.CmbLetterFrom.ItemsSource = range;
        }

        public void SetEndLetterRange(string[] range)
        {
            GtkHelper.PopulateComboBox(this.CmbLetterTo, range);
            //this.CmbLetterTo.ItemsSource = range;
        }

        public void ShowWindow()
        {
            this.Show();
        }

        public void DestroyWindow()
        {
            Close();
            Destroy();
            Dispose();
        }

        private WindowGroup windowGroup;

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
        [UI] private Notebook TabControl;

        private BatchDownloadWindow(Builder builder, Window parent) : base(builder.GetRawOwnedObject("window"))
        {
            builder.Autoconnect(this);
            Title = TextResource.GetText("MENU_BATCH_DOWNLOAD");
            SetDefaultSize(600, 500);
            SetPosition(WindowPosition.Center);
            TransientFor = parent;

            this.windowGroup = new WindowGroup();
            this.windowGroup.AddWindow(this);

            GtkHelper.AttachSafeDispose(this);
            LoadTexts();

            GtkHelper.PopulateComboBox(CmbType, TextResource.GetText("LBL_BATCH_LETTER"), TextResource.GetText("LBL_BATCH_NUM"));
            //GtkHelper.PopulateComboBox(CmbLetterFrom, "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z");
            //GtkHelper.PopulateComboBox(CmbLetterTo, "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z");

            CmbType.Changed += CmbType_Changed;
            CmbLetterFrom.Changed += CmbLetterFrom_Changed;
            CmbLetterTo.Changed += CmbLetterTo_Changed;
            ChkLeadingZero.Toggled += ChkLeadingZero_Toggled;
            TxtAddress.Changed += TxtAddress_Changed;
            TxtAddress.FocusOutEvent += TxtAddress_FocusOutEvent;
            TxtNumberFrom.ValueChanged += Txt_ValueChanged;
            TxtNumberTo.ValueChanged += Txt_ValueChanged;

            CmbType.Active = 0;

            BtnOK.Clicked += BtnOK_Clicked;
            BtnCancel.Clicked += BtnCancel_Clicked;
        }

        private void Txt_ValueChanged(object? sender, EventArgs e)
        {
            OnBatchPatternChange();
        }

        private void TxtAddress_FocusOutEvent(object o, FocusOutEventArgs args)
        {
            OnBatchPatternChange();
        }

        private void TxtAddress_Changed(object? sender, EventArgs e)
        {
            OnBatchPatternChange();
        }

        private void ChkLeadingZero_Toggled(object? sender, EventArgs e)
        {
            OnBatchPatternChange();
        }

        private void CmbLetterTo_Changed(object? sender, EventArgs e)
        {
            OnBatchPatternChange();
        }

        private void CmbLetterFrom_Changed(object? sender, EventArgs e)
        {
            OnBatchPatternChange();
        }

        private void BtnCancel_Clicked(object? sender, EventArgs e)
        {
            DestroyWindow();
        }

        private void BtnOK_Clicked(object? sender, EventArgs e)
        {
            OkClicked?.Invoke(this, EventArgs.Empty);
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

        private void OnBatchPatternChange()
        {
            PatternChanged?.Invoke(this, EventArgs.Empty);
        }

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

        public static BatchDownloadWindow CreateFromGladeFile(Window parent)
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "batch-download-dialog.glade"));
            return new BatchDownloadWindow(builder, parent);
        }
    }
}
