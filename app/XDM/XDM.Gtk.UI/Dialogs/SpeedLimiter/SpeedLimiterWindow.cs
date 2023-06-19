using Gtk;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using UI = Gtk.Builder.ObjectAttribute;
using IoPath = System.IO.Path;
using Translations;
using XDM.GtkUI.Utils;
using XDM.Core.UI;

namespace XDM.GtkUI.Dialogs.SpeedLimiter
{
    public class SpeedLimiterWindow : Dialog, ISpeedLimiterWindow
    {
        public event EventHandler? OkClicked;

        [UI] private Button btnOk, btnCancel;
        [UI] private CheckButton ChkEnabled;
        [UI] private Entry TxtSpeedLimit;
        [UI] private Label LblSpeedLimit;

        public static SpeedLimiterWindow CreateFromGladeFile()
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "speed-limiter-dialog.glade"));
            return new SpeedLimiterWindow(builder);
        }

        public SpeedLimiterWindow(Builder builder) : base(builder.GetRawOwnedObject("window"))
        {
            SetDefaultSize(400, 250);
            builder.Autoconnect(this);
            Title = TextResource.GetText("DESC_ADV_TITLE");
            Modal = true;
            SetPosition(WindowPosition.CenterAlways);

            TxtSpeedLimit!.Text = "0";

#pragma warning disable CS8602 // Dereference of a possibly null reference.
            btnOk.Clicked += BtnOK_Click;
            btnCancel.Clicked += BtnCancel_Click;
#pragma warning restore CS8602 // Dereference of a possibly null reference.

            LoadTexts();

            GtkHelper.AttachSafeDispose(this);
        }

        public int SpeedLimit
        {
            get
            {
                if (Int32.TryParse(TxtSpeedLimit.Text, out int n))
                {
                    return n;
                }
                return 0;
            }
            set => TxtSpeedLimit.Text = value.ToString();
        }

        public bool EnableSpeedLimit
        {
            get => ChkEnabled.Active;
            set => ChkEnabled.Active = value;
        }

        private void BtnOK_Click(object? sender, EventArgs e)
        {
            OkClicked?.Invoke(this, EventArgs.Empty);
            Destroy();
        }

        private void BtnCancel_Click(object? sender, EventArgs e)
        {
            Destroy();
        }

        public void LoadTexts()
        {
            ChkEnabled.Label = TextResource.GetText("MENU_SPEED_LIMITER");
            LblSpeedLimit.Text = TextResource.GetText("MSG_SPEED_LIMIT");
            Title = TextResource.GetText("MENU_SPEED_LIMITER");
            btnOk.Label = TextResource.GetText("MSG_OK");
            btnCancel.Label = TextResource.GetText("ND_CANCEL");
        }

        public void ShowWindow()
        {
            this.ShowAll();
        }
    }
}
