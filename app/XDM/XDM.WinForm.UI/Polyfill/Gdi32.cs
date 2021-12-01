using System;
using System.Runtime.InteropServices;

namespace XDM.WinForm.UI
{
    static partial class Gdi32
    {
        public enum DeviceCapability : int
        {
            BITSPIXEL = 12,
            PLANES = 14,
            LOGPIXELSX = 88,
            LOGPIXELSY = 90
        }

        [DllImport("Gdi32.dll", SetLastError = true, ExactSpelling = true)]
        public static extern int GetDeviceCaps(HDC hDC, DeviceCapability nIndex);

        public enum OBJ : int
        {
            PEN = 1,
            BRUSH = 2,
            DC = 3,
            METADC = 4,
            PAL = 5,
            FONT = 6,
            BITMAP = 7,
            REGION = 8,
            METAFILE = 9,
            MEMDC = 10,
            EXTPEN = 11,
            ENHMETADC = 12,
            ENHMETAFILE = 13,
            COLORSPACE = 14
        }

        [DllImport("Gdi32.dll", ExactSpelling = true)]
        public static extern OBJ GetObjectType(HGDIOBJ h);

        public static OBJ GetObjectType(HandleRef h)
        {
            OBJ result = GetObjectType((HGDIOBJ)h.Handle);
            GC.KeepAlive(h.Wrapper);
            return result;
        }

        public struct HGDIOBJ
        {
            public IntPtr Handle { get; }

            public HGDIOBJ(IntPtr handle) => Handle = handle;

            public bool IsNull => Handle == IntPtr.Zero;

            public static explicit operator IntPtr(HGDIOBJ hgdiobj) => hgdiobj.Handle;
            public static explicit operator HGDIOBJ(IntPtr hgdiobj) => new HGDIOBJ(hgdiobj);

            public static bool operator ==(HGDIOBJ value1, HGDIOBJ value2) => value1.Handle == value2.Handle;
            public static bool operator !=(HGDIOBJ value1, HGDIOBJ value2) => value1.Handle != value2.Handle;
            public override bool Equals(object? obj) => obj is HGDIOBJ hgdiobj && hgdiobj.Handle == Handle;
            public override int GetHashCode() => Handle.GetHashCode();

            public OBJ ObjectType => GetObjectType(this);
        }

        public readonly struct HDC
        {
            public IntPtr Handle { get; }

            public HDC(IntPtr handle) => Handle = handle;

            public bool IsNull => Handle == IntPtr.Zero;

            public static explicit operator IntPtr(HDC hdc) => hdc.Handle;
            public static explicit operator HDC(IntPtr hdc) => new HDC(hdc);
            public static implicit operator HGDIOBJ(HDC hdc) => new HGDIOBJ(hdc.Handle);

            public static bool operator ==(HDC value1, HDC value2) => value1.Handle == value2.Handle;
            public static bool operator !=(HDC value1, HDC value2) => value1.Handle != value2.Handle;
            public override bool Equals(object? obj) => obj is HDC hdc && hdc.Handle == Handle;
            public override int GetHashCode() => Handle.GetHashCode();
        }
    }
}
