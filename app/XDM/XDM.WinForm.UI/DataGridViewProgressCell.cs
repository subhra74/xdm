using System;
using System.ComponentModel;
using System.Drawing;
using System.Windows.Forms;

namespace XDM.WinForm.UI
{
    //https://social.msdn.microsoft.com/Forums/en-US/769ca9d6-1e9d-4d76-8c23-db535b2f19c2/sample-code-datagridview-progress-bar-column?forum=winformsdatacontrols

    class DataGridViewProgressCell : DataGridViewImageCell
    {
        // Used to make custom cell consistent with a DataGridViewImageCell
        static Image emptyImage = new Bitmap(1, 1, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        static SolidBrush b1 = new SolidBrush(AppWinPeer.ProgressBackColor),
            b2 = new SolidBrush(AppWinPeer.ProgressForeColor);

        public int ScaleFactor { get; set; }

        public DataGridViewProgressCell()
        {
            this.ValueType = typeof(int);
            this.ScaleFactor = 1;
        }
        // Method required to make the Progress Cell consistent with the default Image Cell.
        // The default Image Cell assumes an Image as a value, although the value of the Progress Cell is an int.
        protected override object GetFormattedValue(object value,
                            int rowIndex, ref DataGridViewCellStyle cellStyle,
                            TypeConverter valueTypeConverter,
                            TypeConverter formattedValueTypeConverter,
                            DataGridViewDataErrorContexts context)
        {
            return emptyImage;
        }

        protected override void Paint(System.Drawing.Graphics g, System.Drawing.Rectangle clipBounds, System.Drawing.Rectangle cellBounds, int rowIndex, DataGridViewElementStates cellState, object value, object formattedValue, string errorText, DataGridViewCellStyle cellStyle, DataGridViewAdvancedBorderStyle advancedBorderStyle, DataGridViewPaintParts paintParts)
        {
            
            int progressVal = value == null ? 0 : (int)value;

            float percentage = ((float)progressVal / 100.0f); // Need to convert to float before division; otherwise C# returns int which is 0 for anything but 100%.
            // Draws the cell grid
            base.Paint(g, clipBounds, cellBounds,
             rowIndex, cellState, value, formattedValue, errorText,
             cellStyle, advancedBorderStyle, (paintParts & ~DataGridViewPaintParts.ContentForeground));
            var height = Math.Min(cellBounds.Height, ((int)Math.Ceiling(cellBounds.Height*0.1)));
            var y = cellBounds.Height / 2 - height / 2;

            // Create a TextFormatFlags with word wrapping, horizontal center and
            // vertical center specified.
            //TextFormatFlags flags = TextFormatFlags.HorizontalCenter |
            //    TextFormatFlags.VerticalCenter;

            var w1 = cellBounds.Width - cellStyle.Padding.Left - cellStyle.Padding.Right;

            g.FillRectangle(b1, cellBounds.X + cellStyle.Padding.Left, cellBounds.Y + y,
                w1, height);

            var w = Math.Min(w1,
                Convert.ToInt32((percentage * (cellBounds.Width - cellStyle.Padding.Left - cellStyle.Padding.Right))));

            g.FillRectangle(b2, cellBounds.X + cellStyle.Padding.Left, cellBounds.Y + y,
                w, height);
            //TextRenderer.DrawText(g, progressVal.ToString() + "%", cellStyle.Font, cellBounds, Color.Black, flags);

            //if (percentage > 0.0)
            //{
            //    // Draw the progress bar and the text
            //    g.FillRectangle(new SolidBrush(Color.FromArgb(163, 189, 242)), cellBounds.X + cellStyle.Padding.Left, cellBounds.Y + y, Convert.ToInt32((percentage * cellBounds.Width - 4)), height);
            //    TextRenderer.DrawText(g, progressVal.ToString() + "%", cellStyle.Font, cellBounds, cellStyle.ForeColor, flags);
            //    //g.DrawString(progressVal.ToString() + "%", cellStyle.Font, foreColorBrush, cellBounds.X + cellStyle.Padding.Left, cellBounds.Y + 2);
            //}
            //else
            //{
            //    // draw the text
            //    if (this.DataGridView.CurrentRow.Index == rowIndex)
            //        g.DrawString(progressVal.ToString() + "%", cellStyle.Font, new SolidBrush(cellStyle.SelectionForeColor), cellBounds.X + 6, cellBounds.Y + 2);
            //    else
            //        g.DrawString(progressVal.ToString() + "%", cellStyle.Font, foreColorBrush, cellBounds.X + 6, cellBounds.Y + 2);
            //}
        }
    }
}
