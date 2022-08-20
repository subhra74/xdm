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
        public static void LaunchBrowser(string executableName, string args, IEnumerable<string> paths)
        {
            var pathsToCheck = new List<string>(paths);
            var envPaths = Environment.GetEnvironmentVariable("PATH")?.Split(Path.PathSeparator);
            if (envPaths != null)
            {
                pathsToCheck.AddRange(envPaths);
            }

            if (pathsToCheck != null && pathsToCheck.Count > 0)
            {
                foreach (var path in pathsToCheck)
                {
                    Log.Debug($"Finding {executableName} in path: {path}");
                    var file = Path.Combine(path, executableName);
                    if (File.Exists(file))
                    {
                        Exec(file, args);
                        return;
                    }
                }
            }

            throw new FileNotFoundException("Could not launch browser as it is not found in the system");
        }

        public static void LaunchGoogleChrome(string args)
        {
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                LaunchBrowser("chrome.exe", args, new string[]
                {
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"Google\Chrome\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"Google\Chrome\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"Google\Chrome\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"Google\Chrome\Application")
                });
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                LaunchBrowser("google-chrome", args, new string[] { "/usr/bin" });
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                LaunchBrowser("Google Chrome.app", args, new string[] { "/Applications" });
            }
        }

        public static void LaunchFirefox(string args)
        {
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                LaunchBrowser("firefox.exe", args, new string[]
                {
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"Mozilla Firefox"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"Mozilla Firefox"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"Mozilla Firefox"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"Mozilla Firefox")
                });
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                LaunchBrowser("firefox", args, new string[] { "/usr/bin" });
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                LaunchBrowser("Firefox.app", args, new string[] { "/Applications" });
            }
        }

        public static void LaunchMicrosoftEdge(string args)
        {
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                LaunchBrowser("msedge.exe", args, new string[]
                {
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"Microsoft\Edge\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"Microsoft\Edge\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"Microsoft\Edge\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"Microsoft\Edge\Application")
                });
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                LaunchBrowser("microsoft-edge", args, new string[] { "/usr/bin" });
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                LaunchBrowser("Microsoft Edge.app", args, new string[] { "/Applications" });
            }
        }

        public static void LaunchVivaldi(string args)
        {
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                LaunchBrowser("vivaldi.exe", args, new string[]
                {
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"Vivaldi\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"Vivaldi\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"Vivaldi\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"Vivaldi\Application")
                });
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                LaunchBrowser("vivaldi", args, new string[] { "/usr/bin" });
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                LaunchBrowser("Vivaldi.app", args, new string[] { "/Applications" });
            }
        }

        public static void LaunchBraveBrowser(string args)
        {
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                LaunchBrowser("brave.exe", args, new string[]
                {
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"BraveSoftware\Brave-Browser\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"BraveSoftware\Brave-Browser\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"BraveSoftware\Brave-Browser\Application"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"BraveSoftware\Brave-Browser\Application")
                });
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                try
                {
                    LaunchBrowser("brave", args, new string[] { "/usr/bin/brave" });
                }
                catch
                {
                    LaunchBrowser("brave-browser", args, new string[] { "/usr/bin/brave-browser" });
                }
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                LaunchBrowser("Brave Browser.app", args, new string[] { "/Applications" });
            }
        }

        public static void LaunchOperaBrowser(string args)
        {
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                LaunchBrowser("opera.exe", args, new string[]
                {
                    Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432")!, @"Opera"),
                    Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA")!, @"Programs\Opera"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES")!, @"Opera"),
                    Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)")!, @"Opera")
                });
            }
            else if (Environment.OSVersion.Platform == PlatformID.Unix)
            {
                LaunchBrowser("opera", args, new string[] { "/usr/bin" });
            }
            else if (Environment.OSVersion.Platform == PlatformID.MacOSX)
            {
                LaunchBrowser("Opera.app", args, new string[] { "/Applications" });
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
