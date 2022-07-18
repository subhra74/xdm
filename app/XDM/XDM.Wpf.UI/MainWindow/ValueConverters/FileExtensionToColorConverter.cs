using System;
using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using XDM.Common.UI;

namespace XDM.Wpf.UI
{
    [ValueConversion(typeof(string), typeof(SolidColorBrush))]
    internal class FileExtensionToColorConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            var res = IconMap.GetVectorNameForFileType(value as string);
            return Application.Current.TryFindResource("color-" + res);
        }

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
    }
}

