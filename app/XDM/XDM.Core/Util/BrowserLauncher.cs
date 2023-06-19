using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using TraceLog;
using XDM.Core.BrowserMonitoring;

namespace XDM.Core.Util
{
    public static class BrowserLauncher
    {
        public static bool LaunchBrowser(string executableName, string args, IEnumerable<string?> paths)
        {
            var pathsToCheck = new List<string?>(paths);
            var envPaths = Environment.GetEnvironmentVariable("PATH")?.Split(Path.PathSeparator);
            if (envPaths != null)
            {
                pathsToCheck.AddRange(envPaths);
            }

            if (pathsToCheck != null && pathsToCheck.Count > 0)
            {
                foreach (var path in pathsToCheck)
                {
                    if (string.IsNullOrEmpty(path))
                    {
                        continue;
                    }
                    Log.Debug($"Finding {executableName} in path: {path}");
                    var file = Path.Combine(path, executableName);
                    if (File.Exists(file))
                    {
                        Exec(file, args);
                        return true;
                    }
                }
            }

            return false;
        }

        public static bool LaunchBrowser(Browser browser, string args, string? executableLocation)
        {
            switch (browser)
            {
                case Browser.Chrome:
                    return LaunchGoogleChrome(args, executableLocation);
                case Browser.Firefox:
                    return LaunchFirefox(args, executableLocation);
                case Browser.MSEdge:
                    return LaunchMicrosoftEdge(args, executableLocation);
                case Browser.Brave:
                    return LaunchBraveBrowser(args, executableLocation);
                case Browser.Vivaldi:
                    return LaunchVivaldi(args, executableLocation);
                case Browser.Opera:
                    return LaunchOperaBrowser(args, executableLocation);
            }
            return false;
        }

        public static bool LaunchGoogleChrome(string args, string? executableLocation)
        {
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                return LaunchBrowser("chrome.exe", args, new string?[]
                {
                    executableLocation,
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"Google\Chrome\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"Google\Chrome\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"Google\Chrome\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"Google\Chrome\Application")
                });
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                foreach (var exe in GetBrowserExecutableName(Browser.Chrome))
                {
                    if (LaunchBrowser(exe, args, new string?[] { executableLocation, "/usr/bin" }))
                    {
                        return true;
                    }
                }
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                return LaunchBrowser("Google Chrome.app", args, new string?[] { executableLocation, "/Applications" });
            }
            return false;
        }

        public static bool LaunchFirefox(string args, string? executableLocation)
        {
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                var list = new List<string>();
                if (!string.IsNullOrEmpty(executableLocation))
                {
                    list.Add(executableLocation);
                }
                foreach (var env in new string[] { "ProgramW6432", "LOCALAPPDATA", "PROGRAMFILES", "PROGRAMFILES(X86)" })
                {
                    var value = Environment.GetEnvironmentVariable(env);
                    if (!string.IsNullOrEmpty(value))
                    {
                        list.Add(Path.Combine(value, "Mozilla Firefox"));
                    }
                }

                return LaunchBrowser("firefox.exe", args, list);
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                foreach (var exe in GetBrowserExecutableName(Browser.Firefox))
                {
                    if (LaunchBrowser(exe, args, new string?[] { executableLocation, "/usr/bin" }))
                    {
                        return true;
                    }
                }
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                return LaunchBrowser("Firefox.app", args, new string?[] { executableLocation, "/Applications" });
            }
            return false;
        }

        public static bool LaunchMicrosoftEdge(string args, string? executableLocation)
        {
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                return LaunchBrowser("msedge.exe", args, new string?[]
                {
                    executableLocation,
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"Microsoft\Edge\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"Microsoft\Edge\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"Microsoft\Edge\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"Microsoft\Edge\Application")
                });
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                foreach (var exe in GetBrowserExecutableName(Browser.MSEdge))
                {
                    if (LaunchBrowser(exe, args, new string?[] { executableLocation, "/usr/bin" }))
                    {
                        return true;
                    }
                }
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                return LaunchBrowser("Microsoft Edge.app", args, new string?[] { executableLocation, "/Applications" });
            }
            return false;
        }

        public static bool LaunchVivaldi(string args, string? executableLocation)
        {
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                return LaunchBrowser("vivaldi.exe", args, new string?[]
                {
                    executableLocation,
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"Vivaldi\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"Vivaldi\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"Vivaldi\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"Vivaldi\Application")
                });
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                foreach (var exe in GetBrowserExecutableName(Browser.Vivaldi))
                {
                    if (LaunchBrowser(exe, args, new string?[] { executableLocation, "/usr/bin" }))
                    {
                        return true;
                    }
                }
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                return LaunchBrowser("Vivaldi.app", args, new string?[] { executableLocation, "/Applications" });
            }

            return false;
        }

        public static bool LaunchBraveBrowser(string args, string? executableLocation)
        {
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                return LaunchBrowser("brave.exe", args, new string?[]
                {
                    executableLocation,
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"BraveSoftware\Brave-Browser\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"BraveSoftware\Brave-Browser\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"BraveSoftware\Brave-Browser\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"BraveSoftware\Brave-Browser\Application")
                });
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                foreach (var exe in GetBrowserExecutableName(Browser.Brave))
                {
                    if (LaunchBrowser(exe, args, new string?[] { executableLocation, "/usr/bin" }))
                    {
                        return true;
                    }
                }
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                return LaunchBrowser("Brave Browser.app", args, new string?[] { executableLocation, "/Applications" });
            }
            return false;
        }

        public static bool LaunchOperaBrowser(string args, string? executableLocation)
        {
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                return LaunchBrowser("opera.exe", args, new string?[]
                {
                    executableLocation,
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"Opera"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"Programs\Opera"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"Opera"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"Opera")
                });
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                foreach (var exe in GetBrowserExecutableName(Browser.Opera))
                {
                    if (LaunchBrowser(exe, args, new string?[] { executableLocation, "/usr/bin" }))
                    {
                        return true;
                    }
                }
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                return LaunchBrowser("Opera.app", args, new string?[] { executableLocation, "/Applications" });
            }
            return false;
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

        public static IEnumerable<string> GetBrowserExecutableName(Browser browser)
        {
            switch (browser)
            {
                case Browser.Chrome:
                    return new string[] { "chrome", "google-chrome", "google-chrome-stable" };
                case Browser.Firefox:
                    return new string[] { "firefox" };
                case Browser.MSEdge:
                    return new string[] { "msedge" };
                case Browser.Brave:
                    return new string[] { "brave", "brave-browser", "brave-browser-stable" };
                case Browser.Vivaldi:
                    return new string[] { "vivaldi", "vivaldi-browser", "vivaldi-browser-stable" };
                case Browser.Opera:
                    return new string[] { "opera", "opera-browser", "opera-browser-stable" };
                default:
                    break;
            }
            return new string[0];
        }
    }
}
