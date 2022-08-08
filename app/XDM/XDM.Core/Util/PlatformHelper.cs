using Microsoft.Win32;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using TraceLog;

namespace XDM.Core.Util
{
    public static class PlatformHelper
    {
        public static string GetOsDefaultDownloadFolder()
        {
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                return Win32NativeMethods.GetDownloadDirectoryPath();
            }
            else
            {
#if NET5_0_OR_GREATER
                return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
#else
                throw new PlatformNotSupportedException("Please use program which was compiled for dotnet5");
#endif
            }
        }

        public static void OpenBrowser(string url)
        {
            var os = Environment.OSVersion.Platform;
            switch (os)
            {
                case PlatformID.Win32NT:
                    var psiShellEx = new ProcessStartInfo
                    {
                        FileName = url,
                    };
                    psiShellEx.UseShellExecute = true;
                    Process.Start(psiShellEx);
                    break;
#if NET5_0_OR_GREATER
                case PlatformID.Unix:
                    var psi = new ProcessStartInfo
                    {
                        FileName = RuntimeInformation.IsOSPlatform(OSPlatform.OSX) ? "open" : "xdg-open"
                    };
                    psi.Arguments = "\"" + url + "\"";
                    Process.Start(psi);
                    break;
#endif
            }
        }

        public static bool OpenFile(string path)
        {
            if (!File.Exists(path))
            {
                return false;
            }
            try
            {
                var os = Environment.OSVersion.Platform;
                switch (os)
                {
                    case PlatformID.Win32NT:
                        var psiShellEx = new ProcessStartInfo
                        {
                            FileName = path,
                            UseShellExecute = true
                        };
                        Process.Start(psiShellEx);
                        return true;
#if NET5_0_OR_GREATER
                    case PlatformID.Unix:
                        var psi = new ProcessStartInfo
                        {
                            FileName = RuntimeInformation.IsOSPlatform(OSPlatform.OSX) ? "open" : "xdg-open"
                        };
                        psi.Arguments = "\"" + path + "\"";
                        Process.Start(psi);
                        return true;
#endif
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "OpenFile");
            }
            return false;
        }

        public static bool OpenFolder(string path, string? file = null)
        {
            if (file != null)
            {
                if (Directory.Exists(path))
                {
                    try
                    {
                        var os = Environment.OSVersion.Platform;
                        switch (os)
                        {
                            case PlatformID.Win32NT:
                                var psiShellEx = new ProcessStartInfo
                                {
                                    FileName = "explorer",
                                };
                                psiShellEx.Arguments = $"/select, \"{Path.Combine(path, file)}\"";
                                Process.Start(psiShellEx);
                                return true;
#if NET5_0_OR_GREATER
                            case PlatformID.Unix:
                                var psi = new ProcessStartInfo
                                {
                                    FileName = RuntimeInformation.IsOSPlatform(OSPlatform.OSX) ? "open" : "xdg-open"
                                };
                                psi.Arguments = $"\"{path}\"";
                                Process.Start(psi);
                                return true;
#endif
                        }
                    }
                    catch (Exception ex)
                    {
                        Log.Debug(ex, "OpenFolder");
                    }
                }
                return false;
            }
            return OpenFile(path);
        }

        private static string FindChromeExecutableFromRegistry()
        {
            try
            {
                using var regKey = Registry.ClassesRoot.OpenSubKey(@"ChromeHTML\shell\open\command");
                return FileHelper.GetFileNameFromQuote((string)regKey.GetValue(null));
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error fetching chrome location from registry");
            }
            return null;
        }

        public static string GetChromeExecutable()
        {
            var os = Environment.OSVersion.Platform;
            switch (os)
            {
                case PlatformID.Win32NT:
                    var chromeExe = FindChromeExecutableFromRegistry();
                    if (!string.IsNullOrEmpty(chromeExe))
                    {
                        return chromeExe;
                    }
                    var suffix = "Google\\Chrome\\Application\\chrome.exe";
                    foreach (var path in new[] {
                        Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES",
                            EnvironmentVariableTarget.Machine),suffix),
                        Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)",
                            EnvironmentVariableTarget.Machine),suffix),
                        Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA",
                            EnvironmentVariableTarget.User),suffix),
                    })
                    {
                        if (File.Exists(path)) return path;
                    }
                    return null;
#if NET5_0_OR_GREATER
                case PlatformID.Unix:
                    if (RuntimeInformation.IsOSPlatform(OSPlatform.OSX))
                    {
                        const string macChromeExe = "/Applications/Google Chrome.app";
                        if (File.Exists(macChromeExe))
                        {
                            return macChromeExe;
                        }
                    }
                    else
                    {
                        const string linuxChromeExe = "/usr/bin/google-chrome";
                        if (File.Exists(linuxChromeExe))
                        {
                            return linuxChromeExe;
                        }
                    }
                    break;
#endif
            }
            Log.Debug("Chrome executable not found!");
            return null;
        }

        public static string GetFireFoxExecutable()
        {
            var os = Environment.OSVersion.Platform;
            switch (os)
            {
                case PlatformID.Win32NT:
                    var suffix = "Mozilla Firefox\\firefox.exe";
                    foreach (var path in new[] {
                        Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES",
                            EnvironmentVariableTarget.Machine),suffix),
                        Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)",
                            EnvironmentVariableTarget.Machine),suffix),
                        Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA",
                            EnvironmentVariableTarget.User),suffix),
                    })
                    {
                        if (File.Exists(path)) return path;
                    }
                    return null;
#if NET5_0_OR_GREATER
                case PlatformID.Unix:
                    if (RuntimeInformation.IsOSPlatform(OSPlatform.OSX))
                    {
                        const string macFireFoxExe = "/Applications/Firefox.app";
                        if (File.Exists(macFireFoxExe))
                        {
                            return macFireFoxExe;
                        }
                    }
                    else
                    {
                        const string linuxFireFoxExe = "/usr/bin/firefox";
                        if (File.Exists(linuxFireFoxExe))
                        {
                            return linuxFireFoxExe;
                        }
                    }
                    break;
#endif
            }
            Log.Debug("Firefox executable not found!");
            return null;
        }

        public static string GetEdgeExecutable()
        {
            var os = Environment.OSVersion.Platform;
            switch (os)
            {
                case PlatformID.Win32NT:
                    var suffix = @"Edge\Application\msedge.exe";
                    foreach (var path in new[] {
                        Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES",
                            EnvironmentVariableTarget.Machine),suffix),
                        Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)",
                            EnvironmentVariableTarget.Machine),suffix),
                        Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA",
                            EnvironmentVariableTarget.User),suffix),
                    })
                    {
                        if (File.Exists(path)) return path;
                    }
                    break;
            }

            return null;
        }

        public static bool EnableAutoStart(bool enable)
        {
            try
            {
                var os = Environment.OSVersion.Platform;
                if (os == PlatformID.Win32NT)
                {
                    using var hkcuRun = Registry.CurrentUser.CreateSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Run");
                    if (hkcuRun != null)
                    {
                        if (enable)
                        {
                            var xdmExe = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "xdm-app.exe");
                            hkcuRun.SetValue("XDM", $"\"{xdmExe}\" -m");
                        }
                        else
                        {
                            hkcuRun.DeleteValue("XDM", false);
                        }
                    }
                    return true;
                }
#if NET5_0_OR_GREATER
                if (RuntimeInformation.IsOSPlatform(OSPlatform.OSX))
                {
                    return true;
                }
                if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
                {
                    var autoStartDir = GetLinuxDesktopAutoStartDir();
                    if (!Directory.Exists(autoStartDir))
                    {
                        Directory.CreateDirectory(autoStartDir);
                    }
                    File.WriteAllText(Path.Combine(autoStartDir, "xdm-app.desktop"), GetLinuxDesktopFile());
                    return true;
                }
#endif
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
            return false;
        }
#if NET5_0_OR_GREATER
        public static string GetLinuxDesktopAutoStartDir()
        {
            var configDir = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
            if (string.IsNullOrEmpty(configDir))
            {
                configDir = Path.Combine(Environment.GetEnvironmentVariable("HOME") ?? "~", ".config");
            }
            return Path.Combine(configDir, "autostart");
        }

        public static string GetLinuxDesktopFile()
        {
            var appPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "xdm-app");
            var iconPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "svg-icons", "xdm-logo.svg");

            return "[Desktop Entry]\r\n" +
                "Encoding=UTF-8\r\n" +
                "Version=1.0\r\n" +
                "Type=Application\r\n" +
                "Terminal=false\r\n" +
                $"Exec=\"{appPath}\" -m\r\n" +
                "Name=Xtreme Download Manager\r\n" +
                "Comment=Xtreme Download Manager\r\n" +
                "Categories=Network;\r\n" +
                $"Icon=\"{iconPath}\"";
        }

        //     public static void addToStartup()
        //     {
        //         File dir = new File(System.getProperty("user.home"), "Library/LaunchAgents");
        //         dir.mkdirs();
        //         File f = new File(dir, "org.sdg.xdman.plist");
        //         FileOutputStream fs = null;
        //         try
        //         {
        //             fs = new FileOutputStream(f);
        //             fs.write(getStartupPlist().getBytes());
        //         }
        //         catch (Exception e)
        //         {
        //             Logger.log(e);
        //         }
        //         finally
        //         {
        //             try
        //             {
        //                 if (fs != null)
        //                     fs.close();
        //             }
        //             catch (Exception e2)
        //             {
        //             }
        //         }
        //         f.setExecutable(true);
        //     }

        //     public static boolean isAlreadyAutoStart()
        //     {
        //         File f = new File(System.getProperty("user.home"), "Library/LaunchAgents/org.sdg.xdman.plist");
        //         if (!f.exists())
        //             return false;
        //         FileInputStream in = null;
        //         byte[] buf = new byte[(int)f.length()];
        //         try
        //         {
        //in = new FileInputStream(f);
        //             if (in.read(buf) != f.length()) {
        //                 return false;
        //             }
        //         }
        //         catch (Exception e)
        //         {
        //             Logger.log(e);
        //         }
        //         finally
        //         {
        //             try
        //             {
        //                 if (in != null)
        //		in.close();
        //             }
        //             catch (Exception e2)
        //             {
        //             }
        //         }
        //         String str = new String(buf);
        //         String s1 = getProperPath(System.getProperty("java.home"));
        //         String s2 = XDMUtils.getJarFile().getAbsolutePath();
        //         return str.contains(s1) && str.contains(s2);
        //     }

        //     public static void removeFromStartup()
        //     {
        //         File f = new File(System.getProperty("user.home"), "Library/LaunchAgents/org.sdg.xdman.plist");
        //         f.delete();
        //     }

#endif

        public static string GetAppPlatform()
        {
            var os = Environment.OSVersion.Platform;
            if (os == PlatformID.Win32NT)
            {
                return "Windows";
            }
#if NET5_0_OR_GREATER
            if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
            {
                return "Linux";
            }
            if (RuntimeInformation.IsOSPlatform(OSPlatform.OSX))
            {
                return "MacOSX";
            }
#endif
            return "UnsupportedOS";
        }

        public static bool IsAutoStartEnabled()
        {
            try
            {
                var os = Environment.OSVersion.Platform;
                if (os == PlatformID.Win32NT)
                {
                    using var hkcuRun = Registry.CurrentUser.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Run");
                    if (hkcuRun != null)
                    {
                        var command = (string)hkcuRun.GetValue("XDM");
                        var path = FileHelper.GetFileNameFromQuote(command);
                        return !string.IsNullOrEmpty(path) &&
                            path == Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "xdm-app.exe");
                    }
                }
#if NET5_0_OR_GREATER
                if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
                {
                    var autoStartDir = GetLinuxDesktopAutoStartDir();
                    if (!Directory.Exists(autoStartDir))
                    {
                        return false;
                    }
                    var file = Path.Combine(autoStartDir, "xdm-app.desktop");
                    if (!File.Exists(file))
                    {
                        return false;
                    }
                    var text = File.ReadAllText(file);
                    return text.Contains(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "xdm-app"));
                }
#endif
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }

            return false;
        }

        public static void SpawnSubProcess(string executable,
            string[]? args = null,
            bool useShellExecute = false,
            bool createNoWindow = true)
        {
            var psi = new ProcessStartInfo
            {
                FileName = executable,
                UseShellExecute = useShellExecute,
                CreateNoWindow = createNoWindow
            };
            if (args != null && args.Length > 0)
            {
                psi.Arguments = string.Join(" ", args);
            }
            Process.Start(psi);
        }

        public static void ShutDownPC()
        {
            //dbus-send --system --print-reply --dest=org.freedesktop.login1 /org/freedesktop/login1 "org.freedesktop.login1.Manager.PowerOff" boolean:true
            //https://gitlab.xfce.org/xfce/xfce4-power-manager/-/blob/master/src/xfpm-systemd.c
            //https://askubuntu.com/questions/454039/what-command-is-executed-when-shutdown-from-the-graphical-menu-in-14-04
            Log.Debug("Issuing shutdown command...");
            switch (Environment.OSVersion.Platform)
            {
                case PlatformID.Win32NT:
                    SpawnSubProcess("shutdown", new string[] { "/t", "30", "/s" });
                    break;
#if NET5_0_OR_GREATER
                case PlatformID.Unix:
                    if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
                    {
                        var cmd = "org.freedesktop.login1.Manager.PowerOff";
                        SpawnSubProcess("dbus-send", new string[] { "--system", "--print-reply",
                            "--dest=org.freedesktop.login1", "/org/freedesktop/login1", $"\"{cmd}\"","boolean:true"});
                        return;
                    }
                    if (RuntimeInformation.IsOSPlatform(OSPlatform.OSX))
                    {
                        var cmd = "tell app \"System Events\" to shut down";
                        SpawnSubProcess("osascript", new string[] { "-e", $"\"{cmd}\"" });
                    }
                    break;
#endif
                default:
                    Log.Debug("Operating system not supported");
                    break;
            }
        }

        public static void SendKeepAlivePing()
        {
            Log.Debug("Keep alive ping...");
            switch (Environment.OSVersion.Platform)
            {
                case PlatformID.Win32NT:
                    SetThreadExecutionState(EXECUTION_STATE.ES_SYSTEM_REQUIRED);
                    break;
#if NET5_0_OR_GREATER
                case PlatformID.Unix:
                    if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
                    {
                        SpawnSubProcess("dbus-send", new string[] { "--print-reply --type=method_call",
                            "--dest=org.freedesktop.ScreenSaver /ScreenSaver org.freedesktop.ScreenSaver.SimulateUserActivity"});
                        return;
                    }
                    if (RuntimeInformation.IsOSPlatform(OSPlatform.OSX))
                    {
                        SpawnSubProcess("caffeinate", new string[] { "-i -t 3" });
                    }
                    break;
#endif
                default:
                    Log.Debug("Operating system not supported");
                    break;
            }
        }

        public static void RunCommand(string cmd)
        {
            Log.Debug("Running command: " + cmd);
            SpawnSubProcess(FileHelper.QuoteFilePathIfNeeded(cmd));
        }

        public static void RunAntivirus(string cmd, string options, string file)
        {
            Log.Debug("Running antivirus: " + cmd + " " + options + " " + file);
            SpawnSubProcess(FileHelper.QuoteFilePathIfNeeded(cmd), new string[] { options, FileHelper.QuoteFilePathIfNeeded(file) });
        }

        public static string? FindExecutableFromSystemPath(string executableName)
        {
            var values = Environment.GetEnvironmentVariable("PATH");
            foreach (var spath in values?.Split(Path.PathSeparator) ?? new string[] { string.Empty })
            {
                var fullPath = Path.Combine(spath, executableName);
                if (File.Exists(fullPath))
                    return fullPath;
            }
            return null;
        }

        public static void OpenWindowsProxySettings()
        {
            if (Environment.OSVersion.Version.Major == 10)
            {
                var psi = new ProcessStartInfo
                {
                    FileName = "ms-settings:network-proxy",
                    UseShellExecute = true
                };
                Process.Start(psi);
            }
            else
            {
                var psi = new ProcessStartInfo
                {
                    FileName = "rundll32.exe",
                    Arguments = "inetcpl.cpl,LaunchConnectionDialog",
                    UseShellExecute = true
                };
                Process.Start(psi);
            }
        }

        [FlagsAttribute]
        public enum EXECUTION_STATE : uint
        {
            ES_AWAYMODE_REQUIRED = 0x00000040,
            ES_CONTINUOUS = 0x80000000,
            ES_DISPLAY_REQUIRED = 0x00000002,
            ES_SYSTEM_REQUIRED = 0x00000001
            // Legacy flag, should not be used.
            // ES_USER_PRESENT = 0x00000004
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Auto, SetLastError = true)]
        public static extern EXECUTION_STATE SetThreadExecutionState(EXECUTION_STATE esFlags);
    }
}
