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

        public MenuItemWrapper(string name, MenuItem menuItem)
        {
            this.name = name;
            this.menuItem = menuItem;
            this.menuItem.ShowAll();
            menuItem.Activated += (s, e) => Clicked?.Invoke(s, e);
        }
    }
}
