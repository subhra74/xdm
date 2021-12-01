using System;
using System.Runtime.InteropServices;

namespace XDM.WinForm.UI
{
    internal class User32
    {
        // This is only available on Windows 1607 and later. Avoids needing a DC to get the DPI.
        [DllImport("user32.dll", ExactSpelling = true)]
        public static extern uint GetDpiForSystem(); 

        [Flags]
        public enum DCX : uint
        {
            WINDOW = 0x00000001,
            CACHE = 0x00000002,
            NORESETATTRS = 0x00000004,
            CLIPCHILDREN = 0x00000008,
            CLIPSIBLINGS = 0x00000010,
            PARENTCLIP = 0x00000020,
            EXCLUDERGN = 0x00000040,
            INTERSECTRGN = 0x00000080,
            EXCLUDEUPDATE = 0x00000100,
            INTERSECTUPDATE = 0x00000200,
            LOCKWINDOWUPDATE = 0x00000400,
            USESTYLE = 0x00010000,
            VALIDATE = 0x00200000,
        }

        [DllImport("user32.dll", ExactSpelling = true)]
        public static extern Gdi32.HDC GetDC(IntPtr hWnd);

        public static Gdi32.HDC GetDC(HandleRef hWnd)
        {
            Gdi32.HDC dc = GetDC(hWnd.Handle);
            GC.KeepAlive(hWnd.Wrapper);
            return dc;
        }

        /// <summary>
        ///  Helper to scope lifetime of an <see cref="Gdi32.HDC"/> retrieved via <see cref="GetDC(IntPtr)"/> and
        ///  <see cref="GetDCEx(IntPtr, IntPtr, DCX)"/>. Releases the <see cref="Gdi32.HDC"/> (if any) when disposed.
        /// </summary>
        /// <remarks>
        ///  Use in a <see langword="using" /> statement. If you must pass this around, always pass by <see langword="ref" />
        ///  to avoid duplicating the handle and risking a double release.
        /// </remarks>
        public readonly ref struct GetDcScope
        {
            [DllImport("user32.dll", ExactSpelling = true)]
            public static extern Gdi32.HDC GetDCEx(IntPtr hWnd, IntPtr hrgnClip, DCX flags);

            public static Gdi32.HDC GetDCEx(IHandle hWnd, IntPtr hrgnClip, DCX flags)
            {
                Gdi32.HDC result = GetDCEx(hWnd.Handle, hrgnClip, flags);
                GC.KeepAlive(hWnd);
                return result;
            }

            public Gdi32.HDC HDC { get; }
            public IntPtr HWND { get; }

            public GetDcScope(IntPtr hwnd)
            {
                HWND = hwnd;
                HDC = GetDC(hwnd);
            }

            /// <summary>
            ///  Creates a <see cref="Gdi32.HDC"/> using <see cref="GetDCEx(IntPtr, IntPtr, DCX)"/>.
            /// </summary>
            /// <remarks>
            ///  GetWindowDC calls GetDCEx(hwnd, null, DCX_WINDOW | DCX_USESTYLE).
            ///
            ///  GetDC calls GetDCEx(hwnd, null, DCX_USESTYLE) when given a handle. (When given null it has additional
            ///  logic, and can't be replaced directly by GetDCEx.
            /// </remarks>
            public GetDcScope(IntPtr hwnd, IntPtr hrgnClip, DCX flags)
            {
                HWND = hwnd;
                HDC = GetDCEx(hwnd, hrgnClip, flags);
            }

            /// <summary>
            ///  Creates a DC scope for the primary monitor (not the entire desktop).
            /// </summary>
            /// <remarks>
            ///   <see cref="Gdi32.CreateDC(string, string, string, IntPtr)" /> is the API to get the DC for the
            ///   entire desktop.
            /// </remarks>
            public static GetDcScope ScreenDC => new GetDcScope(IntPtr.Zero);

            public bool IsNull => HDC.IsNull;

            public static implicit operator IntPtr(in GetDcScope dcScope) => dcScope.HDC.Handle;
            public static implicit operator Gdi32.HDC(in GetDcScope dcScope) => dcScope.HDC;

            public void Dispose()
            {
                if (!HDC.IsNull)
                {
                    ReleaseDC(HWND, HDC);
                }
            }
        }

        [DllImport("user32.dll", ExactSpelling = true)]
        public static extern int ReleaseDC(IntPtr hWnd, Gdi32.HDC hDC);

        public static int ReleaseDC(HandleRef hWnd, Gdi32.HDC hDC)
        {
            int result = ReleaseDC(hWnd.Handle, hDC);
            GC.KeepAlive(hWnd.Wrapper);
            return result;
        }

        [DllImport("user32.dll", ExactSpelling = true, EntryPoint = "GetThreadDpiAwarenessContext", SetLastError = true)]
        private static extern IntPtr GetThreadDpiAwarenessContextInternal();

        /// <summary>
        ///  Tries to get thread dpi awareness context
        /// </summary>
        /// <returns>Returns thread dpi awareness context if API is available in this version of OS. otherwise, return IntPtr.Zero.</returns>
        public static IntPtr GetThreadDpiAwarenessContext()
        {
            if (OsVersion.IsWindows10_1607OrGreater)
            {
                return GetThreadDpiAwarenessContextInternal();
            }

            // legacy OS that does not have this API available.
            return UNSPECIFIED_DPI_AWARENESS_CONTEXT;
        }

        public static IntPtr UNSPECIFIED_DPI_AWARENESS_CONTEXT = IntPtr.Zero;
        [DllImport("user32.dll", ExactSpelling = true)]
        public static extern bool SetProcessDPIAware();

        [DllImport("user32.dll", ExactSpelling = true)]
        public static extern bool SetProcessDpiAwarenessContext(IntPtr value);

        public static class DPI_AWARENESS_CONTEXT
        {
            public static readonly IntPtr UNAWARE = (IntPtr)(-1);
            public static readonly IntPtr SYSTEM_AWARE = (IntPtr)(-2);
            public static readonly IntPtr PER_MONITOR_AWARE = (IntPtr)(-3);
            public static readonly IntPtr PER_MONITOR_AWARE_V2 = (IntPtr)(-4);
            public static readonly IntPtr UNAWARE_GDISCALED = (IntPtr)(-5);
        }

        [DllImport("user32.dll", ExactSpelling = true)]
        public static extern bool IsValidDpiAwarenessContext(IntPtr value);

        [DllImport("user32.dll", ExactSpelling = true)]
        public static extern bool IsProcessDPIAware();

        [DllImport("user32.dll", ExactSpelling = true, EntryPoint = "AreDpiAwarenessContextsEqual", SetLastError = true)]
        private static extern bool AreDpiAwarenessContextsEqualInternal(IntPtr dpiContextA, IntPtr dpiContextB);

        /// <summary>
        ///  Tries to compare two DPIawareness context values. Return true if they were equal.
        ///  Return false when they are not equal or underlying OS does not support this API.
        /// </summary>
        /// <returns>true/false</returns>
        public static bool AreDpiAwarenessContextsEqual(IntPtr dpiContextA, IntPtr dpiContextB)
        {
            if (dpiContextA == UNSPECIFIED_DPI_AWARENESS_CONTEXT && dpiContextB == UNSPECIFIED_DPI_AWARENESS_CONTEXT)
            {
                return true;
            }

            if (OsVersion.IsWindows10_1607OrGreater)
            {
                return AreDpiAwarenessContextsEqualInternal(dpiContextA, dpiContextB);
            }

            return false;
        }
    }
}
