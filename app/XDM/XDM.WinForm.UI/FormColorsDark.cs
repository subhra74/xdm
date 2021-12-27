using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Text;

namespace XDM.WinForm.UI
{
    internal class FormColorsDark : IFormColors
    {
        public Color ToolbarButtonForeColor { get; } = Color.Gray;
        public Color ToolbarButtonMouseOverBackColor { get; } = Color.FromArgb(50, 55, 57);
        public Color ToolbarButtonMouseDownBackColor { get; } = Color.FromArgb(32, 35, 32);
        public Color ToolbarButtonDisabledForeColor { get; } = Color.DimGray;
        public Color ToolbarBackColor { get; } = Color.FromArgb(29, 33, 37);//Color.FromArgb(20, 22, 24);

        public Color SearchButtonColor { get; } = Color.DimGray;

        public Color DataGridViewForeColor { get; } = Color.DarkGray;
        public Color DataGridViewBackColor { get; } = Color.FromArgb(24, 26, 28);//Color.FromArgb(29, 33, 37);
        public Color DataGridViewSelectionBackColor { get; } = Color.FromArgb(59, 63, 67);
        public Color DataGridViewSelectionForeColor { get; } = Color.LightGray;
        public Color DataGridViewHeaderForeColor { get; } = Color.DimGray;
        public Color FooterBackColor { get; } = Color.FromArgb(29, 33, 37);
        public Color FooterForeColor { get; } = Color.DarkGray;
        public Color BorderColor { get; } = Color.Black;

        public Color TextForeColor { get; } = Color.White;
        public Color TextBackColor { get; } = Color.FromArgb(36, 41, 46);

        public Color IconColor { get; } = Color.Gray;

        public Color ProgressBarBackColor { get; } = Color.FromArgb(50, 50, 50);
        public Color ProgressBarForeColor { get; } = Color.DodgerBlue;

        public Color BackColor { get; } = Color.FromArgb(36, 41, 46);
        public Color ButtonColor { get; } = Color.FromArgb(47, 54, 61);
        public Color ForeColor { get; } = Color.White;

    }
}
