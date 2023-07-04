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
using XDM.Core;
using XDM.Core.UI;
using XDM.GtkUI.Utils;
using Translations;
using UI = Gtk.Builder.ObjectAttribute;

namespace XDM.GtkUI.Dialogs.AdvancedDownload
{
    internal class AdvancedDownloadDialog : Dialog
    {
        [UI] private Button btnOk = null;
        [UI] private Button btnCancel = null;
        [UI] private Entry TxtUserName = null;
        [UI] private Entry TxtPassword = null;
        [UI] private ComboBox CmbProxyType = null;
        [UI] private Entry TxtProxyHost = null;
        [UI] private Entry TxtProxyPort = null;
        [UI] private Entry TxtProxyUser = null;
        [UI] private Entry TxtProxyPassword = null;
        [UI] private CheckButton ChkEnabled = null;
        [UI] private CheckButton ChkRememberAuth = null;
        [UI] private Entry TxtSpeedLimit = null;

        [UI] private Label tabPage1 = null;
        [UI] private Label tabPage2 = null;
        [UI] private Label tabPage3 = null;

        [UI] private Label LblUser = null;
        [UI] private Label LblPassword = null;
        [UI] private Label LblProxy = null;
        [UI] private Label LblProxyHost = null;
        [UI] private Label LblProxyPort = null;
        [UI] private Label LblProxyUser = null;
        [UI] private Label LblProxyPass = null;
        [UI] private Label LblSpeedLimit = null;

        private WindowGroup group;

        public static AdvancedDownloadDialog CreateFromGladeFile(Window parent, WindowGroup group)
        {
            return new AdvancedDownloadDialog(GtkHelper.GetBuilder("advanced-download-dialog"), parent, group);
        }

        public AdvancedDownloadDialog(Builder builder, Window parent, WindowGroup group) : base(builder.GetRawOwnedObject("dialog"))// base(TextResource.GetText("DESC_ADV_TITLE"), parent, DialogFlags.Modal)
        {
            SetDefaultSize(550, 450);
            builder.Autoconnect(this);
            Title = TextResource.GetText("DESC_ADV_TITLE");
            Modal = true;
            SetPosition(WindowPosition.CenterAlways);
            TransientFor = parent;
            this.group = group;
            this.group.AddWindow(this);

            GtkHelper.PopulateComboBox(CmbProxyType!,
                TextResource.GetText("NET_SYSTEM_PROXY"),
                TextResource.GetText("ND_NO_PROXY"),
                TextResource.GetText("ND_MANUAL_PROXY"));

            GtkHelper.ConfigurePasswordField(TxtPassword);
            GtkHelper.ConfigurePasswordField(TxtProxyPassword);
            TxtSpeedLimit!.Text = "0";
            CmbProxyType!.Changed += CmbProxyType_Changed;

            btnOk.Clicked += BtnOK_Click;
            btnCancel.Clicked += BtnCancel_Click;

            LoadTexts();

            GtkHelper.AttachSafeDispose(this);
        }

        public AuthenticationInfo? Authentication
        {
            get
            {
                if (string.IsNullOrEmpty(TxtUserName.Text))
                {
                    return null;
                }
                return new AuthenticationInfo
                {
                    UserName = TxtUserName.Text,
                    Password = TxtPassword.Text
                };
            }
            set
            {
                if (value.HasValue)
                {
                    TxtUserName.Text = value.Value.UserName;
                    TxtPassword.Text = value.Value.Password;
                }
            }
        }

        public ProxyInfo? Proxy
        {
            get
            {
                var selectedIndex = CmbProxyType.Active;
                if (selectedIndex == 1)
                {
                    return new ProxyInfo { ProxyType = ProxyType.Direct };
                }
                if (selectedIndex == 0)
                {
                    return new ProxyInfo { ProxyType = ProxyType.System };
                }
                if (selectedIndex == 2 &&
                    !string.IsNullOrEmpty(TxtProxyHost.Text) &&
                    Int32.TryParse(TxtProxyPort.Text, out _))
                {
                    return new ProxyInfo
                    {
                        ProxyType = ProxyType.Custom,
                        Host = TxtProxyHost.Text,
                        Port = Int32.Parse(TxtProxyPort.Text),
                        UserName = TxtProxyUser.Text,
                        Password = TxtProxyPassword.Text
                    };
                }
                return null;
            }
            set
            {
                SetProxy(value ?? Config.Instance.Proxy);
            }
        }

        private void SetProxy(ProxyInfo? proxy)
        {
            CmbProxyType.Active = (int)(proxy?.ProxyType ?? 0);
            TxtProxyHost.Text = proxy?.Host;
            TxtProxyPort.Text = proxy?.Port.ToString();
            TxtProxyUser.Text = proxy?.UserName;
            TxtProxyPassword.Text = proxy?.Password;
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

        public bool Result { get; set; } = false;

        private void BtnOK_Click(object sender, EventArgs e)
        {
            Result = true;
            this.group.RemoveWindow(this);
            Visible = false;
        }

        private void BtnCancel_Click(object sender, EventArgs e)
        {
            Result = false;
            this.group.RemoveWindow(this);
            Visible = false;
        }

        private void CmbProxyType_Changed(object? sender, EventArgs e)
        {
            TxtProxyUser.IsEditable = TxtProxyPassword.IsEditable = TxtProxyHost.IsEditable =
               TxtProxyPort.IsEditable = CmbProxyType.Active == 2;
        }

        //private void LoadTranslations(Builder builder)
        //{
        //    builder.
        //}

        //private void LoadTranslations(Widget widget)
        //{
        //    if (widget == null)
        //    {
        //        return;
        //    }

        //    if (!string.IsNullOrEmpty(widget.GetProperty("name").ToString()))
        //    {
        //        var text = TextResource.GetText(widget.Name);
        //        if (!string.IsNullOrEmpty(text))
        //        {
        //            switch (widget)
        //            {
        //                case Button btn:
        //                    btn.Label = text;
        //                    break;
        //                case Label lbl:
        //                    lbl.Text = text;
        //                    break;
        //            }
        //        }
        //    }

        //    if (widget is not Container c)
        //    {
        //        return;
        //    }

        //    var children = c.Children;
        //    if (children != null)
        //    {
        //        foreach (var child in children)
        //        {
        //            LoadTranslations(child);
        //        }
        //    }
        //}

        public void LoadTexts()
        {

            tabPage1.Text = TextResource.GetText("ND_AUTH");
            tabPage2.Text = TextResource.GetText("DESC_NET4");
            tabPage3.Text = TextResource.GetText("MENU_SPEED_LIMITER");

            LblUser.Text = TextResource.GetText("DESC_USER");
            LblPassword.Text = TextResource.GetText("DESC_PASS");
            ChkRememberAuth.Label = TextResource.GetText("ND_AUTH_REMEMBER");

            LblProxy.Text = TextResource.GetText("DESC_NET4");
            LblProxyHost.Text = TextResource.GetText("PROXY_HOST");
            LblProxyUser.Text = TextResource.GetText("DESC_NET7");
            LblProxyPort.Text = TextResource.GetText("PROXY_PORT");
            LblProxyPass.Text = TextResource.GetText("DESC_NET8");
            ChkEnabled.Label = TextResource.GetText("MENU_SPEED_LIMITER");
            LblSpeedLimit.Text = TextResource.GetText("MSG_SPEED_LIMIT");

            btnOk.Label = TextResource.GetText("MSG_OK");
            btnCancel.Label = TextResource.GetText("ND_CANCEL");
            //button3.Text = TextResource.GetText("ND_SYSTEM_PROXY");
        }
    }
}
