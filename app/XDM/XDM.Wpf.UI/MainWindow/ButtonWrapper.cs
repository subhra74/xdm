using System;
using System.Windows;
using System.Windows.Controls;
using XDM.Core.UI;

namespace XDM.Wpf.UI
{
    internal class ButtonWrapper : IButton
    {
        private Button button;

        public ButtonWrapper(Button button)
        {
            this.button = button;
            this.button.Click += Button_Click;
        }

        private void Button_Click(object sender, RoutedEventArgs e)
        {
            Clicked?.Invoke(sender, e);
        }

        public bool Visible
        {
            get => button.Visibility == Visibility.Visible;
            set => button.Visibility = value ? Visibility.Visible : Visibility.Collapsed;
        }

        public bool Enable
        {
            get => button.IsEnabled;
            set => button.IsEnabled = value;
        }

        public event EventHandler? Clicked;
    }
}
