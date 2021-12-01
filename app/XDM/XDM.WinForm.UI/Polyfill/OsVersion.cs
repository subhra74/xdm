// Licensed to the .NET Foundation under one or more agreements.
// The .NET Foundation licenses this file to you under the MIT license.
// See the LICENSE file in the project root for more information.



namespace XDM.WinForm.UI
{
    internal static class OsVersion
    {
        private static NtDll.RTL_OSVERSIONINFOEX s_versionInfo = InitVersion();

        private static NtDll.RTL_OSVERSIONINFOEX InitVersion()
        {
            // We use RtlGetVersion as it isn't subject to version lie. GetVersion
            // won't tell you the real version unless the launching exe is manifested
            // with the latest OS version.

            NtDll.RtlGetVersion(out NtDll.RTL_OSVERSIONINFOEX info);
            return info;
        }

        /// <summary>
        ///  Is Windows 10 first release or later. (Threshold 1, build 10240, version 1507)
        /// </summary>
        public static bool IsWindows10_1507OrGreater
            => s_versionInfo.dwMajorVersion >= 10 && s_versionInfo.dwBuildNumber >= 10240;

        /// <summary>
        ///  Is Windows 10 Anniversary Update or later. (Redstone 1, build 14393, version 1607)
        /// </summary>
        public static bool IsWindows10_1607OrGreater
            => s_versionInfo.dwMajorVersion >= 10 && s_versionInfo.dwBuildNumber >= 14393;

        /// <summary>
        ///  Is Windows 10 Creators Update or later. (Redstone 2, build 15063, version 1703)
        /// </summary>
        public static bool IsWindows10_1703OrGreater
            => s_versionInfo.dwMajorVersion >= 10 && s_versionInfo.dwBuildNumber >= 15063;

        /// <summary>
        ///  Is Windows 8.1 or later.
        /// </summary>
        public static bool IsWindows8_1OrGreater
            => s_versionInfo.dwMajorVersion >= 10
                || (s_versionInfo.dwMajorVersion == 6 && s_versionInfo.dwMinorVersion == 3);

        /// <summary>
        ///  Is Windows 8 or later.
        /// </summary>
        public static bool IsWindows8OrGreater
            => s_versionInfo.dwMajorVersion >= 8;
    }
}