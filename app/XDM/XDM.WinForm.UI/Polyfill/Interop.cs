// Licensed to the .NET Foundation under one or more agreements.
// The .NET Foundation licenses this file to you under the MIT license.
// See the LICENSE file in the project root for more information.

using System;
using System.Runtime.InteropServices;

namespace XDM.WinForm.UI
{
    internal interface IHandle
    {
        public IntPtr Handle { get; }
    }
    internal class Interop
    {
        




    }

    internal class NtDll
    {
        /// <summary>
        ///  Version info structure for <see cref="RtlGetVersion(out RTL_OSVERSIONINFOEX)" />
        /// </summary>
        /// <remarks>
        ///  Note that this structure is the exact same defintion as OSVERSIONINFOEX.
        /// </remarks>
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        internal unsafe struct RTL_OSVERSIONINFOEX
        {
            internal uint dwOSVersionInfoSize;
            internal uint dwMajorVersion;
            internal uint dwMinorVersion;
            internal uint dwBuildNumber;
            internal uint dwPlatformId;
            internal fixed char szCSDVersion[128];
            internal ushort wServicePackMajor;
            internal ushort wServicePackMinor;
            internal ushort wSuiteMask;
            internal byte wProductType;
            internal byte wReserved;
        }

        [DllImport("ntdll.dll", EntryPoint = "RtlGetVersion", ExactSpelling = true)]
        private static extern int RtlGetVersionInternal(ref RTL_OSVERSIONINFOEX lpVersionInformation);

        internal static unsafe int RtlGetVersion(out RTL_OSVERSIONINFOEX versionInfo)
        {
            versionInfo = new RTL_OSVERSIONINFOEX
            {
                dwOSVersionInfoSize = (uint)sizeof(RTL_OSVERSIONINFOEX)
            };
            return RtlGetVersionInternal(ref versionInfo);
        }

        [DllImport("ntdll.dll", ExactSpelling = true)]
        public unsafe static extern uint RtlNtStatusToDosError(int Status);
    }

    internal enum HRESULT : int
    {
        S_OK = 0,
        S_FALSE = 1,
        DRAGDROP_S_DROP = 0x00040100,
        DRAGDROP_S_CANCEL = 0x00040101,
        DRAGDROP_S_USEDEFAULTCURSORS = 0x00040102,

        E_NOTIMPL = unchecked((int)0x80004001),
        E_NOINTERFACE = unchecked((int)0x80004002),
        E_POINTER = unchecked((int)0x80004003),
        E_ABORT = unchecked((int)0x80004004),
        E_FAIL = unchecked((int)0x80004005),

        // These are CLR HRESULTs
        InvalidArgFailure = unchecked((int)0x80008081),
        CoreHostLibLoadFailure = unchecked((int)0x80008082),
        CoreHostLibMissingFailure = unchecked((int)0x80008083),
        CoreHostEntryPointFailure = unchecked((int)0x80008084),
        CoreHostCurHostFindFailure = unchecked((int)0x80008085),
        CoreClrResolveFailure = unchecked((int)0x80008087),
        CoreClrBindFailure = unchecked((int)0x80008088),
        CoreClrInitFailure = unchecked((int)0x80008089),
        CoreClrExeFailure = unchecked((int)0x8000808a),
        LibHostExecModeFailure = unchecked((int)0x80008090),
        LibHostSdkFindFailure = unchecked((int)0x80008091),
        LibHostInvalidArgs = unchecked((int)0x80008092),
        InvalidConfigFile = unchecked((int)0x80008093),
        AppArgNotRunnable = unchecked((int)0x80008094),
        AppHostExeNotBoundFailure = unchecked((int)0x80008095),
        FrameworkMissingFailure = unchecked((int)0x80008096),
        HostApiFailed = unchecked((int)0x80008097),
        HostApiBufferTooSmall = unchecked((int)0x80008098),
        LibHostUnknownCommand = unchecked((int)0x80008099),
        LibHostAppRootFindFailure = unchecked((int)0x8000809a),
        SdkResolverResolveFailure = unchecked((int)0x8000809b),
        FrameworkCompatFailure = unchecked((int)0x8000809c),
        FrameworkCompatRetry = unchecked((int)0x8000809d),

        RPC_E_CHANGED_MODE = unchecked((int)0x80010106),
        DISP_E_MEMBERNOTFOUND = unchecked((int)0x80020003),
        DISP_E_PARAMNOTFOUND = unchecked((int)0x80020004),
        DISP_E_UNKNOWNNAME = unchecked((int)0x80020006),
        DISP_E_EXCEPTION = unchecked((int)0x80020009),
        DISP_E_UNKNOWNLCID = unchecked((int)0x8002000C),
        DISP_E_DIVBYZERO = unchecked((int)0x80020012),
        TYPE_E_BADMODULEKIND = unchecked((int)0x800288BD),
        STG_E_INVALIDFUNCTION = unchecked((int)0x80030001),
        STG_E_FILENOTFOUND = unchecked((int)0x80030002),
        STG_E_ACCESSDENIED = unchecked((int)0x80030005),
        STG_E_INVALIDPARAMETER = unchecked((int)0x80030057),
        STG_E_INVALIDFLAG = unchecked((int)0x800300FF),
        OLE_E_ADVISENOTSUPPORTED = unchecked((int)0x80040003),
        OLE_E_NOCONNECTION = unchecked((int)0x80040004),
        OLE_E_PROMPTSAVECANCELLED = unchecked((int)0x8004000C),
        OLE_E_INVALIDRECT = unchecked((int)0x8004000D),
        DV_E_FORMATETC = unchecked((int)0x80040064),
        DV_E_TYMED = unchecked((int)0x80040069),
        DV_E_DVASPECT = unchecked((int)0x8004006B),
        DRAGDROP_E_NOTREGISTERED = unchecked((int)0x80040100),
        DRAGDROP_E_ALREADYREGISTERED = unchecked((int)0x80040101),
        VIEW_E_DRAW = unchecked((int)0x80040140),
        INPLACE_E_NOTOOLSPACE = unchecked((int)0x800401A1),
        CO_E_OBJNOTREG = unchecked((int)0x800401FB),
        CO_E_OBJISREG = unchecked((int)0x800401FC),
        E_ACCESSDENIED = unchecked((int)0x80070005),
        E_OUTOFMEMORY = unchecked((int)0x8007000E),
        E_INVALIDARG = unchecked((int)0x80070057),
        ERROR_CANCELLED = unchecked((int)0x800704C7),
    }

    internal static class HResultExtensions
    {
        public static bool Succeeded(this HRESULT hr) => hr >= 0;

        public static bool Failed(this HRESULT hr) => hr < 0;

        public static string AsString(this HRESULT hr)
            => Enum.IsDefined(typeof(HRESULT), hr)
                ? $"HRESULT {hr} [0x{(int)hr:X} ({(int)hr:D})]"
                : $"HRESULT [0x{(int)hr:X} ({(int)hr:D})]";
    }
}

