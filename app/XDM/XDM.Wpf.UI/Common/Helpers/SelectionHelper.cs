using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Windows.Controls;

namespace XDM.Wpf.UI.Common.Helpers
{
    internal static class SelectionHelper
    {
        public static int[] GetSelectedIndices(this ListView lv)
        {
            if (lv.SelectedItems.Count < 1) return new int[0];
            var list = new List<int>(lv.SelectedItems.Count);
            var selectedItems = lv.SelectedItems;
            var allItems = lv.Items;
            for (int i = 0; i < selectedItems.Count; i++)
            {
                var index = allItems.IndexOf(selectedItems[i]);
                list.Add(index);
            }
            list.Sort();
            return list.ToArray();
        }
    }
}
