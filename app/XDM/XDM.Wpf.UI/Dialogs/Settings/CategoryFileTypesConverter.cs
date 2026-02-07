using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Windows.Data;

namespace XDM.Wpf.UI.Dialogs.Settings
{
    [ValueConversion(typeof(IEnumerable<string>), typeof(string))]
    internal class CategoryFileTypesConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            return string.Join(",", ((IEnumerable<string>)value).ToArray());
        }

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
    }
}
