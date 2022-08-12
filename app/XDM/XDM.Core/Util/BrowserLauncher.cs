using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using TraceLog;

namespace XDM.Core.Util
{
    public static class BrowserLauncher
    {
        public static void LaunchGoogleChrome(string args)
        {
            string[]? paths = null;
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                paths = new string[]{
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"Google\Chrome\Application\chrome.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"Google\Chrome\Application\chrome.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"Google\Chrome\Application\chrome.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"Google\Chrome\Application\chrome.exe")
                };
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                paths = new string[]{
                    "/usr/bin/google-chrome"
                };
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                paths = new string[]{
                    "/Applications/Google Chrome.app"
                };
            }

            if (paths != null && paths.Length > 0)
            {
                foreach (var path in paths)
                {
                    Log.Debug($"Finding chrome in path: {path}");
                    if (File.Exists(path))
                    {
                        Exec(path, args);
                        break;
                    }
                }
            }
        }

        public static void LaunchFirefox(string args)
        {
            string[]? paths = null;
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                paths = new string[]{
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"Mozilla Firefox\firefox.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"Mozilla Firefox\firefox.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"Mozilla Firefox\firefox.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"Mozilla Firefox\firefox.exe")
                };
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                paths = new string[]{
                    "/usr/bin/firefox"
                };
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                paths = new string[]{
                    "/Applications/Firefox.app"
                };
            }

            if (paths != null && paths.Length > 0)
            {
                foreach (var path in paths)
                {
                    Log.Debug($"Finding firefox in path: {path}");
                    if (File.Exists(path))
                    {
                        Exec(path, args);
                        break;
                    }
                }
            }
        }

        public static void LaunchMicrosoftEdge(string args)
        {
            string[]? paths = null;
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                paths = new string[]{
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"Microsoft\Edge\Application\msedge.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"Microsoft\Edge\Application\msedge.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"Microsoft\Edge\Application\msedge.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"Microsoft\Edge\Application\msedge.exe")
                };
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                paths = new string[]{
                    "/usr/bin/microsoft-edge"
                };
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                paths = new string[]{
                    "/Applications/Microsoft Edge.app"
                };
            }

            if (paths != null && paths.Length > 0)
            {
                foreach (var path in paths)
                {
                    Log.Debug($"Finding edge in path: {path}");
                    if (File.Exists(path))
                    {
                        Exec(path, args);
                        break;
                    }
                }
            }
        }

        public static void LaunchVivaldi(string args)
        {
            string[]? paths = null;
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                paths = new string[]{
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"Vivaldi\Application\vivaldi.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"Vivaldi\Application\vivaldi.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"Vivaldi\Application\vivaldi.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"Vivaldi\Application\vivaldi.exe")
                };
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                paths = new string[]{
                    "/usr/bin/vivaldi"
                };
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                paths = new string[]{
                    "/Applications/Vivaldi.app"
                };
            }

            if (paths != null && paths.Length > 0)
            {
                foreach (var path in paths)
                {
                    Log.Debug($"Finding Vivaldi in path: {path}");
                    if (File.Exists(path))
                    {
                        Exec(path, args);
                        break;
                    }
                }
            }
        }

        public static void LaunchBraveBrowser(string args)
        {
            string[]? paths = null;
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                paths = new string[]{
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"BraveSoftware\Brave-Browser\Application\brave.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"BraveSoftware\Brave-Browser\Application\brave.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"BraveSoftware\Brave-Browser\Application\brave.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"BraveSoftware\Brave-Browser\Application\brave.exe")
                };
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                paths = new string[]{
                    "/usr/bin/brave",
                    "/usr/bin/brave-browser"
                };
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                paths = new string[]{
                    "/Applications/Brave Browser.app"
                };
            }

            if (paths != null && paths.Length > 0)
            {
                foreach (var path in paths)
                {
                    Log.Debug($"Finding vivaldi in path: {path}");
                    if (File.Exists(path))
                    {
                        Exec(path, args);
                        break;
                    }
                }
            }
        }

        public static void LaunchOperaBrowser(string args)
        {
            string[]? paths = null;
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                paths = new string[]{
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"Programs\Opera\opera.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"Programs\Opera\opera.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"Programs\Opera\opera.exe"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"Programs\Opera\opera.exe")
                };
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                paths = new string[]{
                    "/usr/bin/opera"
                };
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                paths = new string[]{
                    "/Applications/Opera.app"
                };
            }

            if (paths != null && paths.Length > 0)
            {
                foreach (var path in paths)
                {
                    Log.Debug($"Finding vivaldi in path: {path}");
                    if (File.Exists(path))
                    {
                        Exec(path, args);
                        break;
                    }
                }
            }
        }

        private static void Exec(string path, string args)
        {
            Log.Debug($"{path} {args}");

            var psi = new ProcessStartInfo
            {
                FileName = path,
                UseShellExecute = false,
                Arguments = args
            };

            Process.Start(psi);
        }
    }
}
