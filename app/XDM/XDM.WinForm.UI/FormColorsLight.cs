using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Text;

namespace XDM.WinForm.UI
{
    internal class FormColorsLight : IFormColors
    {
        public Color ToolbarButtonForeColor { get; } = Color.DimGray;
        public Color ToolbarButtonMouseOverBackColor { get; } = Color.FromArgb(230, 230, 230);
        public Color ToolbarButtonMouseDownBackColor { get; } = Color.FromArgb(226, 226, 226);
        public Color ToolbarButtonDisabledForeColor { get; } = Color.DarkGray;
        public Color ToolbarBackColor { get; } = Color.FromArgb(246, 246, 246);

        public Color SearchButtonColor { get; } = Color.DarkGray;

        public Color DataGridViewForeColor { get; } = Color.DimGray;
        public Color DataGridViewBackColor { get; } = Color.White;
        public Color DataGridViewSelectionBackColor { get; } = Color.FromArgb(242, 242, 242);
        public Color DataGridViewSelectionForeColor { get; } = Color.Black;
        public Color DataGridViewHeaderForeColor { get; } = Color.DarkGray;
        public Color BorderColor { get; } = Color.FromArgb(235, 235, 235);

        public Color FooterBackColor { get; } = Color.White;
        public Color FooterForeColor { get; } = Color.DimGray;

        public Color TextForeColor { get; } = Color.DimGray;
        public Color TextBackColor { get; } = Color.White;

        public Color IconColor { get; } = Color.DodgerBlue;

        public Color ProgressBarBackColor { get; } = Color.FromArgb(230, 230, 230);
        public Color ProgressBarForeColor { get; } = Color.DodgerBlue;

        public Color BackColor => SystemColors.Control;

        public Color ButtonColor => SystemColors.ButtonFace;

        public Color ForeColor => SystemColors.ControlText;
    }
}
