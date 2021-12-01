using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;


namespace XDM.WinForm.UI
{
    internal static partial class SHCore
    {
        [DllImport(Libraries.SHCore, ExactSpelling = true)]
        public static extern HRESULT GetProcessDpiAwareness(IntPtr hprocess, out PROCESS_DPI_AWARENESS value);

        public enum PROCESS_DPI_AWARENESS : int
        {
            UNAWARE = 0,
            SYSTEM_AWARE = 1,
            PER_MONITOR_AWARE = 2
        }

        [DllImport(Libraries.SHCore, ExactSpelling = true)]
        public static extern HRESULT SetProcessDpiAwareness(PROCESS_DPI_AWARENESS value);
    }

    class Libraries
    {
        public const string SHCore = "SHCore.dll";
    }
}

    
