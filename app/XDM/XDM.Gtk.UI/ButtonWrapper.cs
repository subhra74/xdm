using Gtk;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using XDM.Core.Lib.UI;

namespace XDM.GtkUI
{
    internal class ButtonWrapper : IButton
    {
        private readonly ToolButton button;

        public ButtonWrapper(ToolButton button)
        {
            this.button = button;
            button.Clicked += (s, e) =>
            {
                this.Clicked?.Invoke(s, e);
            };
        }

        public bool Visible { get => button.Visible; set => button.Visible = value; }

        public bool Enable
        {
            get => button.Sensitive;
            set
            {
                button.Sensitive = value;
            }
        }

        public event EventHandler? Clicked;
    }
}
