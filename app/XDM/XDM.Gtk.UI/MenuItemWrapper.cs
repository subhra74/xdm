using Gtk;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using XDM.Core.Lib.UI;

namespace XDM.GtkUI
{
    internal class MenuItemWrapper : IMenuItem
    {
        private MenuItem menuItem;
        private string name;
        public string Name => name;
        public MenuItem MenuItem => menuItem;

        public bool Enabled
        {
            get => menuItem.IsSensitive;
            set => menuItem.Sensitive = value;
        }

        public event EventHandler? Clicked;

        public MenuItemWrapper(string name, string text, bool visible = true)
        {
            this.name = name;
            this.menuItem = new MenuItem(text);
            this.menuItem.Name = name;
            if (visible)
            {
                this.menuItem.ShowAll();
            }
            this.menuItem.Activated += Mi_Click;
        }

        private void Mi_Click(object? sender, EventArgs e)
        {
            this.Clicked?.Invoke(sender, e);
        }
    }
}
