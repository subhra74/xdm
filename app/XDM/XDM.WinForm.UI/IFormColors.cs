using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Text;

namespace XDM.WinForm.UI
{
    internal interface IFormColors
    {
        public Color ToolbarButtonForeColor { get; }
        public Color ToolbarButtonMouseOverBackColor { get; }
        public Color ToolbarButtonMouseDownBackColor { get; }
        public Color ToolbarButtonDisabledForeColor { get; }
        public Color ToolbarBackColor { get; }

        public Color SearchButtonColor { get; }

        public Color DataGridViewForeColor { get; }
        public Color DataGridViewBackColor { get; }
        public Color DataGridViewSelectionBackColor { get; }
        public Color DataGridViewSelectionForeColor { get; }
        public Color DataGridViewHeaderForeColor { get; }
        public Color BorderColor { get; }

        public Color FooterBackColor { get; }
        public Color FooterForeColor { get; }

        public Color TextForeColor { get; }
        public Color TextBackColor { get; }

        public Color IconColor { get; }

        public Color ProgressBarBackColor { get; }
        public Color ProgressBarForeColor { get; }

        public Color BackColor { get; }
        public Color ButtonColor { get; }
        public Color ForeColor { get; }
    }
}
