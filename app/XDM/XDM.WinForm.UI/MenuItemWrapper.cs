using System;
using System.Windows.Forms;
using XDM.Core.Lib.UI;

namespace XDM.WinForm.UI
{
    public class MenuItemWrapper : IMenuItem
    {
        private ToolStripMenuItem menuItem;
        private string name;
        public string Name => name;
        public bool Enabled
        {
            get => menuItem.Enabled;
            set => menuItem.Enabled = value;
        }
        public event EventHandler? Clicked;

        public MenuItemWrapper(string name, ToolStripMenuItem menuItem)
        {
            this.name = name;
            this.menuItem = menuItem;
            menuItem.Click += (s, e) => Clicked?.Invoke(s, e);
        }
    }
}
