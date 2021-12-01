using System;
using System.Windows.Forms;
using XDM.Core.Lib.UI;

namespace XDM.WinForm.UI
{
    internal class ButtonWrapper : IButton
    {
        private readonly Button button;
        private readonly IFormColors formColors;

        public ButtonWrapper(Button button, IFormColors formColors)
        {
            this.button = button;
            this.formColors = formColors;
            button.Click += (s, e) =>
            {
                if ("enabled" == (string)button.Tag)
                {
                    this.Clicked?.Invoke(s, e);
                }
            };
        }

        public bool Visible { get => button.Visible; set => button.Visible = value; }

        public bool Enable
        {
            get => "enabled" == (string)button.Tag;
            set
            {
                if (value)
                {
                    ButtonHelper.EnableButton(button, formColors);
                }
                else
                {
                    ButtonHelper.DisableButton(button, formColors);
                }
            }
        }

        public event EventHandler? Clicked;
    }
}
