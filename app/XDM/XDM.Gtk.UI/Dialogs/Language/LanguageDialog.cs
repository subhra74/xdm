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

namespace XDM.GtkUI.Dialogs.Language
{
    public class LanguageDialog : Dialog
    {
        [UI] private Label Label1, Label2;
        [UI] private ComboBox CmbLanguage;
        [UI] private Button BtnOk, BtnCancel;

        public bool Result { get; set; } = false;

        private WindowGroup group;

        private LanguageDialog(Builder builder, Window parent, WindowGroup group) : base(builder.GetRawOwnedObject("dialog"))
        {
            builder.Autoconnect(this);

            Modal = true;
            SetPosition(WindowPosition.CenterAlways);
            TransientFor = parent;
            this.group = group;
            this.group.AddWindow(this);

            GtkHelper.AttachSafeDispose(this);

            Label1.Text = TextResource.GetText("MSG_LANG1");
            Label2.Text = TextResource.GetText("MSG_LANG2");

            BtnOk.Clicked += BtnOk_Clicked;
            BtnCancel.Clicked += BtnCancel_Clicked;

            BtnOk.Label = TextResource.GetText("MSG_OK");
            BtnCancel.Label = TextResource.GetText("ND_CANCEL");

            Title = TextResource.GetText("MENU_LANG");
            SetDefaultSize(400, 200);

            var indexFile = IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, IoPath.Combine("Lang", "index.txt"));
            var items = new List<string>();
            var n = 0;
            var c = 0;
            if (File.Exists(indexFile))
            {
                var lines = File.ReadAllLines(indexFile);
                foreach (var line in lines)
                {
                    var index = line.IndexOf("=");
                    if (index > 0)
                    {
                        var name = line.Substring(0, index);
                        items.Add(name);
                        if (name == Config.Instance.Language)
                        {
                            c = n;
                        }
                        n++;
                    }
                }
                if (items.Count > 0)
                {
                    GtkHelper.PopulateComboBoxGeneric<string>(CmbLanguage, items.ToArray());
                    CmbLanguage.Active = c;
                }
            }
        }

        private void BtnCancel_Clicked(object? sender, EventArgs e)
        {
            Result = false;
            this.group.RemoveWindow(this);
            Dispose();
        }

        private void BtnOk_Clicked(object? sender, EventArgs e)
        {
            Result = true;
            var name = GtkHelper.GetSelectedComboBoxValue<string>(CmbLanguage);
            if (!string.IsNullOrEmpty(name))
            {
                Config.Instance.Language = name;
                Config.SaveConfig();
            }
            this.group.RemoveWindow(this);
            Dispose();
        }

        public static LanguageDialog CreateFromGladeFile(Window parent, WindowGroup group)
        {
            return new LanguageDialog(GtkHelper.GetBuilder("language-dialog"), parent, group);
        }
    }
}
