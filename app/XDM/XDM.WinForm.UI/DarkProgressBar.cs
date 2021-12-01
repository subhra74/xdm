using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Windows.Forms;
using System.Drawing;

namespace XDM.WinForm.UI
{
    public class DarkProgressBar : ProgressBar
    {
        private Brush bgBrush, fgBrush;

        public DarkProgressBar()
        {
            this.SetStyle(ControlStyles.UserPaint, true);
            bgBrush = new SolidBrush(this.BackColor);
            fgBrush = new SolidBrush(this.ForeColor);
        }

        public override Color BackColor
        {
            get => base.BackColor;
            set
            {
                bgBrush = new SolidBrush(value); 
                base.BackColor = value;
            }
        }

        public override Color ForeColor
        {
            get => base.ForeColor;
            set
            {
                fgBrush = new SolidBrush(value);
                base.ForeColor = value;
            }
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Rectangle rec = new Rectangle(0, 0, this.Width, this.Height);
            double scaleFactor = (((double)Value - (double)Minimum) / ((double)Maximum - (double)Minimum));
            e.Graphics.FillRectangle(this.bgBrush, rec);
            rec.Width = (int)((rec.Width * scaleFactor));
            e.Graphics.FillRectangle(fgBrush, 0, 0, rec.Width, rec.Height);
        }
    }
}
