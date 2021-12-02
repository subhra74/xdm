using System;
using System.Collections.Generic;
using System.Drawing.Text;
using System.IO;
using System.Linq;
using System.Text;

namespace XDM.WinForm.UI
{
    public static class GlobalFontCollection
    {
        public static readonly PrivateFontCollection FaFontInstance;
        public static readonly PrivateFontCollection RiFontInstance;
        public static readonly PrivateFontCollection ImFontInstance;

        static GlobalFontCollection()
        {
            FaFontInstance = new PrivateFontCollection();
            RiFontInstance = new PrivateFontCollection();
            ImFontInstance = new PrivateFontCollection();

            FaFontInstance.AddFontFile(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, @"FontAwesome\fontawesome-webfont.ttf"));
            RiFontInstance.AddFontFile(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, @"FontAwesome\remixicon.ttf"));
            ImFontInstance.AddFontFile(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, @"FontAwesome\icomoon.ttf"));
        }

        public static void Dispose()
        {
            FaFontInstance.Dispose();
            RiFontInstance.Dispose();
            ImFontInstance.Dispose();
        }
    }
}
