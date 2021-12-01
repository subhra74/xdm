using System.Windows.Forms;

namespace XDM.WinForm.UI
{
    public class DataGridViewProgressColumn : DataGridViewImageColumn
    {
        public DataGridViewProgressColumn(int scaleFactor)
        {
            CellTemplate = new DataGridViewProgressCell() { ScaleFactor = scaleFactor };
        }

        public int ScaleFactor
        {
            set => (CellTemplate as DataGridViewProgressCell).ScaleFactor = value;
        }
    }
}
