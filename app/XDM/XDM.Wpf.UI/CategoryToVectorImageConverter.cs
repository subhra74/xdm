using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using XDM.Common.UI;

namespace XDM.Wpf.UI
{
    [ValueConversion(typeof(string), typeof(Geometry))]
    internal class CategoryToVectorImageConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            var geom = Application.Current.TryFindResource(value);
            return geom;
        }

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
    }
}
