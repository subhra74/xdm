using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;

namespace XDM.WinForm.UI
{
    internal static class ButtonHelper
    {
        public static Color ParentBackColor { get; set; }

        public static void SetFlatStyle(Button button, IFormColors formColors)
        {
            button.FlatAppearance.MouseOverBackColor = formColors.ToolbarButtonMouseOverBackColor;
            button.FlatAppearance.MouseDownBackColor = formColors.ToolbarButtonMouseDownBackColor;
        }

        public static Dictionary<Button, (Image ImgEnabled, Image ImgDisabled)> ButtonStateIcons { get; set; } = new();

        public static void DisableButton(Button button, IFormColors formColors)
        {
            button.ForeColor = formColors.ToolbarButtonDisabledForeColor;//Color.DarkGray;
            button.Image = ButtonStateIcons[button].ImgDisabled;
            button.FlatAppearance.MouseOverBackColor = ParentBackColor;
            button.FlatAppearance.MouseDownBackColor = ParentBackColor;
            button.Tag = "disabled";
        }

        public static void EnableButton(Button button, IFormColors formColors)
        {
            button.ForeColor = formColors.ToolbarButtonForeColor; //Color.DimGray;
            SetFlatStyle(button, formColors);
            button.Image = ButtonStateIcons[button].ImgEnabled;
            button.Tag = "enabled";
        }
    }
}
