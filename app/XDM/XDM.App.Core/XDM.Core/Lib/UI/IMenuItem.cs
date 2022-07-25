using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;


namespace XDM.Core.UI
{
    public interface IMenuItem
    {
        public string Name { get; }

        public bool Enabled { get; set; }

        public event EventHandler Clicked;
    }
}
