using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;


namespace XDM.Core.Lib.UI
{
    public interface IButton
    {
        bool Visible { get; set; }

        bool Enable { get; set; }

        event EventHandler Clicked;
    }
}
