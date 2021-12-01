using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Windows.Forms;
using XDM.WinForm.UI.FormHelper;

namespace XDM.WinForm.UI
{
    internal static class FontIcon
    {
        public static Bitmap Create(Control control, Font font, string text, Color foreColor, int paddingW = 0, int paddingH = 0)
        {
            using var g1 = control.CreateGraphics();
            var sizeF = g1.MeasureString(text, font);

            var width = (int)Math.Ceiling(sizeF.Width + DpiCompat.ToDeviceUnits(control, paddingW));
            var height = (int)Math.Ceiling(sizeF.Height + DpiCompat.ToDeviceUnits(control, paddingH));

            var bitmap = new Bitmap(width, height, System.Drawing.Imaging.PixelFormat.Format32bppPArgb);
            using Graphics g = Graphics.FromImage(bitmap);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
            g.TextRenderingHint = TextRenderingHint.AntiAlias;

            //Console.WriteLine(height + " " + sizeF.Height);
            //Console.WriteLine((int)Math.Ceiling((height - sizeF.Height) / 2));

            //using var bgBrush = new SolidBrush(backColor);
            using var fgBrush = new SolidBrush(foreColor);
            //g.FillRectangle(bgBrush, 0, 0, width, height);

            //StringFormat sf = new StringFormat();
            //sf.LineAlignment = StringAlignment.Center;
            //sf.Alignment = StringAlignment.Center;
            //sf.FormatFlags = StringFormatFlags.NoWrap | StringFormatFlags.FitBlackBox | StringFormatFlags.FitBlackBox;

            g.DrawString(text, font, fgBrush,
                (int)Math.Ceiling((width - sizeF.Width) / 2),
                (int)Math.Ceiling((height - sizeF.Height) / 2 + DpiCompat.ToDeviceUnits(control, 1)));

            //TextFormatFlags flags = TextFormatFlags.HorizontalCenter |
            //    TextFormatFlags.VerticalCenter| TextFormatFlags.;
            //g.DrawString(text, font, fgBrush, new Rectangle(0, 0, bitmap.Width, bitmap.Height), sf);
            //TextRenderer.DrawText(g, text, font, new Rectangle(0, 0, bitmap.Width, bitmap.Height), foreColor, flags);
            return bitmap;
        }
    }
}
