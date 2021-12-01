using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Windows.Forms;

namespace XDM.WinForm.UI
{
    internal class DarkMenuColorTable : ProfessionalColorTable
    {
        private Color backColor = Color.FromArgb(34, 34, 34);
        public DarkMenuColorTable(Color backColor)
        {
            this.backColor = backColor;
        }

        public override Color MenuItemSelected => Color.DodgerBlue;
        public override Color MenuItemBorder => Color.DodgerBlue;
        public override Color SeparatorDark => Color.Black;
        public override Color SeparatorLight => Color.Black;
        public override Color MenuBorder => Color.Black;

        public override Color ImageMarginGradientBegin => this.backColor;
        public override Color ImageMarginGradientEnd => this.backColor;
        public override Color ImageMarginGradientMiddle => this.backColor;
        public override Color MenuItemPressedGradientMiddle => this.backColor;
    }
}
