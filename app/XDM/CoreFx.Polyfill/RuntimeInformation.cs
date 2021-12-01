// Licensed to the .NET Foundation under one or more agreements.
// The .NET Foundation licenses this file to you under the MIT license.

using System.Reflection;

namespace System.Runtime.InteropServices
{
    public static partial class RuntimeInformation
    {
        /// <summary>
        /// Indicates whether the current application is running on the specified platform.
        /// </summary>
        public static bool IsOSPlatform(OSPlatform osPlatform) => IsOSPlatform(osPlatform.Name);

        /// <summary>
        /// Indicates whether the current application is running on the specified platform.
        /// </summary>
        /// <param name="platform">Case-insensitive platform name. Examples: Browser, Linux, FreeBSD, Android, iOS, macOS, tvOS, watchOS, Windows.</param>
        public static bool IsOSPlatform(string platform)
        {
            if (platform == null)
            {
                throw new ArgumentNullException(nameof(platform));
            }
            return platform.Equals("WINDOWS", StringComparison.OrdinalIgnoreCase);
        }
    }
}