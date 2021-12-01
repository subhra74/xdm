using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Windows.Forms;

namespace XDM.WinForm.UI.FormHelper
{
    internal class FontImageHelper
    {
        public static Bitmap FontToBitmap(Control control, Font font, string text, Color foreColor, int paddingW = 0, int paddingH = 0)
        {
            using var g1 = control.CreateGraphics();
            var sizeF = g1.MeasureString(text, font);

            var width = (int)sizeF.Width + DpiCompat.ToDeviceUnits(control, paddingW);
            var height = (int)sizeF.Height + DpiCompat.ToDeviceUnits(control, paddingH);

            var bitmap = new Bitmap(width, height, System.Drawing.Imaging.PixelFormat.Format32bppPArgb);
            using Graphics g = Graphics.FromImage(bitmap);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
            g.TextRenderingHint = TextRenderingHint.AntiAlias;

            //using var bgBrush = new SolidBrush(backColor);
            using var fgBrush = new SolidBrush(foreColor);
            //g.FillRectangle(bgBrush, 0, 0, width, height);

            StringFormat sf = new StringFormat();
            sf.LineAlignment = StringAlignment.Center;
            sf.Alignment = StringAlignment.Center;
            sf.FormatFlags = StringFormatFlags.NoWrap | StringFormatFlags.FitBlackBox | StringFormatFlags.FitBlackBox;

            g.DrawString(text, font, fgBrush, (sizeF.Width + DpiCompat.ToDeviceUnits(control, paddingW)) / 2 - sizeF.Width / 2,
                (sizeF.Height + DpiCompat.ToDeviceUnits(control, paddingH)) / 2 - sizeF.Height / 2);

            //TextFormatFlags flags = TextFormatFlags.HorizontalCenter |
            //    TextFormatFlags.VerticalCenter| TextFormatFlags.;
            //g.DrawString(text, font, fgBrush, new Rectangle(0, 0, bitmap.Width, bitmap.Height), sf);
            //TextRenderer.DrawText(g, text, font, new Rectangle(0, 0, bitmap.Width, bitmap.Height), foreColor, flags);
            return bitmap;
        }
    }
}
