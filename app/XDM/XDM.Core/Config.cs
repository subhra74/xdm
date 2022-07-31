using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using TraceLog;
using XDM.Core.IO;
using XDM.Core.Util;

namespace XDM.Core
{
    public class Config
    {
        private static Config instance;
        private static object lockObj = new();
        public static Config Instance
        {
            get
            {
                if (instance == null)
                {
                    lock (lockObj)
                    {
                        if (instance == null)
                        {
                            LoadConfig();
                        }
                    }
                }

                return instance!;
            }

            private set
            {
                instance = value;
            }
        }

        public static string DataDir { get; set; }

        public bool IsBrowserMonitoringEnabled { get; set; } = true;

        public static string DefaultFallbackUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/97.0.4692.99 Safari/537.36";

        public string FallbackUserAgent { get; set; } = DefaultFallbackUserAgent;

        public static string[] DefaultVideoExtensions => new string[]
            {
                "MP4", "M3U8", "F4M", "WEBM", "OGG", "MP3", "AAC", "FLV", "MKV", "DIVX",
                "MOV", "MPG", "MPEG","OPUS"
            };

        public string[] VideoExtensions { get; set; }

        public static string[] DefaultFileExtensions => new string[]
            {
                "3GP", "7Z", "AVI", "BZ2", "DEB", "DOC", "DOCX", "EXE", "ISO",
                "MSI", "PDF", "PPT", "PPTX", "RAR", "RPM", "XLS", "XLSX", "SIT", "SITX", "TAR", "JAR", "ZIP", "XZ"
            };

        public string[] FileExtensions { get; set; }

        public static string[] DefaultBlockedHosts => new string[]
            {
                "update.microsoft.com","windowsupdate.com","thwawte.com"
            };

        public string[] BlockedHosts { get; set; }

        public string Language { get; set; } = "English";

        public bool AllowSystemDarkTheme { get; set; } = false;

        private Config()
        {
            VideoExtensions = DefaultVideoExtensions;
            FileExtensions = DefaultFileExtensions;
            BlockedHosts = DefaultBlockedHosts;
        }

        public List<string> RecentFolders { get; set; } = new List<string>();

        public FolderSelectionMode FolderSelectionMode { get; set; }

        public FileConflictResolution FileConflictResolution { get; set; }

        public int MaxRetry { get; set; } = 10;

        public int RetryDelay { get; set; } = 10;

        public int MaxParallelDownloads { get; set; } = 1;

        public bool ShowProgressWindow { get; set; } = true;

        public bool ShowDownloadCompleteWindow { get; set; } = true;

        public bool StartDownloadAutomatically { get; set; } = false;

        public bool FetchServerTimeStamp { get; set; } = false;

        public bool MonitorClipboard { get; set; } = false;

        public int MinVideoSize { get; set; } = 1 * 1024;

        public string TempDir { get; set; }

        public int NetworkTimeout { get; set; } = 30;

        public int MaxSegments { get; set; } = 8;

        public int DefaltDownloadSpeed { get; set; } = 0;

        public bool EnableSpeedLimit { get; set; } = false;

        public bool ShutdownAfterAllFinished { get; set; } = false;

        public bool KeepPCAwake { get; set; } = true;

        public bool RunCommandAfterCompletion { get; set; } = false;

        public string AfterCompletionCommand { get; set; }

        public bool ScanWithAntiVirus { get; set; } = false;

        public string AntiVirusExecutable { get; set; }

        public string AntiVirusArgs { get; set; }

        public ProxyInfo? Proxy { get; set; }

        public bool DoubleClickOpenFile { get; set; } = false;

        public bool RunOnLogon
        {
            get => PlatformHelper.IsAutoStartEnabled();
            set => PlatformHelper.EnableAutoStart(value);
        }

        public string UserSelectedDownloadFolder { get; set; }

        public string DefaultDownloadFolder { get; set; } =
            PlatformHelper.GetOsDefaultDownloadFolder();

        public static IEnumerable<Category> DefaultCategories = new[]
        {
            new Category
            {
                Name="CAT_DOCUMENTS",
                DisplayName="Document",
                FileExtensions=new HashSet<string>
                {
                    ".DOC", ".DOCX", ".PDF", ".MD", ".XLSX",".XLS", ".CBZ"
                },
                DefaultFolder=Path.Combine(PlatformHelper.GetOsDefaultDownloadFolder(),
                    "Documents"),
                IsPredefined=true
            },
            new Category
            {
                Name="CAT_MUSIC",
                DisplayName="Music",
                FileExtensions=new HashSet<string>
                {
                    ".MP3", ".AAC",".MPA",".WMA",".MIDI"
                },
                DefaultFolder=Path.Combine(PlatformHelper.GetOsDefaultDownloadFolder(),"Music"),
                IsPredefined=true
            },
            new Category
            {
                Name="CAT_VIDEOS",
                DisplayName="Video",
                FileExtensions=new HashSet<string>
                {
                    ".MP4",  ".WEBM", ".OGG",  ".FLV", ".MKV", ".DIVX",
                    ".MOV", ".MPG", ".MPEG",".OPUS",".AVI",".WMV"
                },
                DefaultFolder=Path.Combine(PlatformHelper.GetOsDefaultDownloadFolder(),"Video"),
                IsPredefined=true
            },
            new Category
            {
                Name="CAT_COMPRESSED",
                DisplayName="Compressed",
                FileExtensions=new HashSet<string>
                {
                    ".7Z", ".ZIP", ".RAR", ".BZ2", ".GZ",".XZ", ".TAR"
                },
                DefaultFolder=Path.Combine(PlatformHelper.GetOsDefaultDownloadFolder(),"Compressed"),
                IsPredefined=true
            },
            new Category
            {
                Name="CAT_PROGRAMS",
                DisplayName="Application",
                FileExtensions=new HashSet<string>
                {
                    ".EXE", ".DEB", ".RPM", ".MSI"
                },
                DefaultFolder=Path.Combine(PlatformHelper.GetOsDefaultDownloadFolder(),"Programs"),
                IsPredefined=true
            },
            //new Category
            //{
            //    Name="Other",
            //    DisplayName="Other",
            //    FileExtensions=new HashSet<string>
            //    {
            //    }
            //}
        };

        public IEnumerable<Category> Categories = DefaultCategories;

        public IEnumerable<PasswordEntry> UserCredentials { get; set; } = new List<PasswordEntry>();

        public static void LoadConfig(string? path = null)
        {
            DataDir = path ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".xdman");
            instance = new Config
            {
                TempDir = Path.Combine(DataDir, "temp")
            };
            try
            {
                if (!Directory.Exists(DataDir))
                {
                    Directory.CreateDirectory(DataDir);
                }

                var bytes = TransactedIO.ReadBytes("settings.dat", DataDir);
                if (bytes != null)
                {
                    using var ms = new MemoryStream(bytes);
                    using var reader = new BinaryReader(ms);
                    ConfigIO.DeserializeConfig(instance, reader);
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }


            //var json = TransactedIO.Read("settings.json", Config.DataDir);
            //Config? instance = null;
            //if (json != null)
            //{
            //    instance = JsonConvert.DeserializeObject<Config>(
            //                json, new JsonSerializerSettings
            //                {
            //                    MissingMemberHandling = MissingMemberHandling.Ignore,
            //                    ConstructorHandling = ConstructorHandling.AllowNonPublicDefaultConstructor
            //                });
            //}
            //if (instance == null)
            //{
            //    Instance = new Config
            //    {
            //        TempDir = Path.Combine(Config.DataDir, "temp")
            //    };
            //}
            //else
            //{
            //    Instance = instance;
            //}

            //var path = Path.Combine(Config.DataDir, "settings.json");
            //if (File.Exists(path))
            //{
            //    try
            //    {
            //        var instance = JsonConvert.DeserializeObject<Config>(
            //                File.ReadAllText(path), new JsonSerializerSettings
            //                {
            //                    MissingMemberHandling = MissingMemberHandling.Ignore,
            //                    ConstructorHandling = ConstructorHandling.AllowNonPublicDefaultConstructor
            //                });
            //        if (instance != null)
            //        {
            //            Instance = instance;
            //            return;
            //        }
            //    }
            //    catch (Exception exx)
            //    {
            //        Log.Debug(exx, "Error loading config");
            //    }
            //}
            //Instance = new Config
            //{
            //    TempDir = Path.Combine(Config.DataDir, "temp")
            //};
        }

        //private static void PopulateConfig32(Config instance, BinaryReader r)
        //{
        //    instance.AfterCompletionCommand = XDM.Messaging.StreamHelper.ReadString(r);
        //    instance.AntiVirusArgs = XDM.Messaging.StreamHelper.ReadString(r);
        //    instance.AntiVirusExecutable = XDM.Messaging.StreamHelper.ReadString(r);
        //    var count = r.ReadInt32();
        //    instance.BlockedHosts = new string[count];
        //    for (int i = 0; i < count; i++)
        //    {
        //        instance.BlockedHosts[i] = r.ReadString();
        //    }
        //    count = r.ReadInt32();
        //    var list = new List<Category>(count);
        //    for (int i = 0; i < count; i++)
        //    {
        //        var category = new Category
        //        {
        //            DefaultFolder = XDM.Messaging.StreamHelper.ReadString(r),
        //            DisplayName = XDM.Messaging.StreamHelper.ReadString(r),
        //            FileExtensions = new HashSet<string>(),
        //        };
        //        var c2 = r.ReadInt32();
        //        for (int j = 0; j < c2; j++)
        //        {
        //            category.FileExtensions.Add(r.ReadString());
        //        }
        //        category.IsPredefined = r.ReadBoolean();
        //        category.Name = r.ReadString();
        //        list.Add(category);
        //    }
        //    instance.Categories = list;
        //    instance.DefaultDownloadFolder = XDM.Messaging.StreamHelper.ReadString(r);
        //    instance.EnableSpeedLimit = r.ReadBoolean();
        //    instance.FetchServerTimeStamp = r.ReadBoolean();
        //    instance.FileConflictResolution = (FileConflictResolution)r.ReadInt32();
        //    count = r.ReadInt32();
        //    instance.FileExtensions = new string[count];
        //    for (int i = 0; i < count; i++)
        //    {
        //        instance.FileExtensions[i] = r.ReadString();
        //    }
        //    instance.FolderSelectionMode = (FolderSelectionMode)r.ReadInt32();
        //    instance.DefaltDownloadSpeed = r.ReadInt32();
        //    instance.IsBrowserMonitoringEnabled = r.ReadBoolean();
        //    instance.KeepPCAwake = r.ReadBoolean();
        //    instance.Language = r.ReadString();
        //    instance.MaxParallelDownloads = r.ReadInt32();
        //    instance.MaxRetry = r.ReadInt32();
        //    instance.MaxSegments = r.ReadInt32();
        //    instance.MinVideoSize = r.ReadInt32();
        //    instance.MonitorClipboard = r.ReadBoolean();
        //    instance.NetworkTimeout = r.ReadInt32();
        //    count = r.ReadInt32();
        //    instance.RecentFolders = new List<string>(count);
        //    for (int i = 0; i < count; i++)
        //    {
        //        instance.RecentFolders.Add(r.ReadString());
        //    }
        //    instance.RetryDelay = r.ReadInt32();
        //    instance.RunCommandAfterCompletion = r.ReadBoolean();
        //    instance.RunOnLogon = r.ReadBoolean();
        //    instance.ScanWithAntiVirus = r.ReadBoolean();
        //    instance.ShowDownloadCompleteWindow = r.ReadBoolean();
        //    instance.ShowProgressWindow = r.ReadBoolean();
        //    instance.ShutdownAfterAllFinished = r.ReadBoolean();
        //    instance.StartDownloadAutomatically = r.ReadBoolean();
        //    instance.TempDir = XDM.Messaging.StreamHelper.ReadString(r);
        //    count = r.ReadInt32();
        //    var list2 = new List<PasswordEntry>(count);
        //    for (int i = 0; i < count; i++)
        //    {
        //        var passwordEntry = new PasswordEntry
        //        {
        //            Host = XDM.Messaging.StreamHelper.ReadString(r),
        //            User = XDM.Messaging.StreamHelper.ReadString(r),
        //            Password = XDM.Messaging.StreamHelper.ReadString(r)
        //        };
        //        list2.Add(passwordEntry);
        //    }
        //    instance.UserCredentials = list2;
        //    count = r.ReadInt32();
        //    instance.VideoExtensions = new string[count];
        //    for (int i = 0; i < count; i++)
        //    {
        //        instance.VideoExtensions[i] = r.ReadString();
        //    }
        //    instance.Proxy = ProxyInfoSerializer.Deserialize(r);
        //    instance.AllowSystemDarkTheme = r.ReadBoolean();
        //}

        public static void SaveConfig()
        {
            ConfigIO.SerializeConfig();
        }

        //public static void SaveConfig3()
        //{
        //    using var ms = new MemoryStream();
        //    using var writer = new BinaryWriter(ms);
        //    writer.Write(Instance.AfterCompletionCommand ?? string.Empty);
        //    writer.Write(Instance.AntiVirusArgs ?? string.Empty);
        //    writer.Write(Instance.AntiVirusExecutable ?? string.Empty);
        //    var count = Instance.BlockedHosts?.Length ?? 0;
        //    writer.Write(count);
        //    for (int i = 0; i < count; i++)
        //    {
        //        writer.Write(Instance.BlockedHosts![i]);
        //    }
        //    count = Instance.Categories.Count();
        //    writer.Write(count);
        //    foreach (var category in Instance.Categories)
        //    {
        //        writer.Write(category.DefaultFolder);
        //        writer.Write(category.DisplayName ?? string.Empty);
        //        count = category.FileExtensions.Count();
        //        writer.Write(count);
        //        foreach (var ext in category.FileExtensions)
        //        {
        //            writer.Write(ext);
        //        }
        //        writer.Write(category.IsPredefined);
        //        writer.Write(category.Name);
        //    }
        //    writer.Write(Instance.DefaultDownloadFolder ?? string.Empty);
        //    writer.Write(Instance.EnableSpeedLimit);
        //    writer.Write(Instance.FetchServerTimeStamp);
        //    writer.Write((int)Instance.FileConflictResolution);
        //    count = Instance.FileExtensions.Length;
        //    writer.Write(count);
        //    foreach (var ext in Instance.FileExtensions)
        //    {
        //        writer.Write(ext);
        //    }
        //    writer.Write((int)Instance.FolderSelectionMode);
        //    writer.Write(Instance.DefaltDownloadSpeed);
        //    writer.Write(Instance.IsBrowserMonitoringEnabled);
        //    writer.Write(Instance.KeepPCAwake);
        //    writer.Write(Instance.Language);
        //    writer.Write(Instance.MaxParallelDownloads);
        //    writer.Write(Instance.MaxRetry);
        //    writer.Write(Instance.MaxSegments);
        //    writer.Write(Instance.MinVideoSize);
        //    writer.Write(Instance.MonitorClipboard);
        //    writer.Write(Instance.NetworkTimeout);
        //    count = Instance.RecentFolders.Count;
        //    writer.Write(count);
        //    foreach (var recentFolder in Instance.RecentFolders)
        //    {
        //        writer.Write(recentFolder);
        //    }
        //    writer.Write(Instance.RetryDelay);
        //    writer.Write(Instance.RunCommandAfterCompletion);
        //    writer.Write(Instance.RunOnLogon);
        //    writer.Write(Instance.ScanWithAntiVirus);
        //    writer.Write(Instance.ShowDownloadCompleteWindow);
        //    writer.Write(Instance.ShowProgressWindow);
        //    writer.Write(Instance.ShutdownAfterAllFinished);
        //    writer.Write(Instance.StartDownloadAutomatically);
        //    writer.Write(Instance.TempDir);
        //    count = Instance.UserCredentials.Count();
        //    writer.Write(count);
        //    foreach (var pe in Instance.UserCredentials)
        //    {
        //        writer.Write(pe.Host ?? string.Empty);
        //        writer.Write(pe.User ?? string.Empty);
        //        writer.Write(pe.Password ?? string.Empty);
        //    }
        //    count = Instance.VideoExtensions.Length;
        //    writer.Write(count);
        //    foreach (var ext in Instance.VideoExtensions)
        //    {
        //        writer.Write(ext);
        //    }
        //    //ProxyInfoSerializer.Serialize(Instance.Proxy, writer);
        //    //writer.Write(Instance.AllowSystemDarkTheme);
        //    writer.Close();
        //    ms.Close();
        //    TransactedIO.WriteBytes(ms.ToArray(), "settings.db", Config.DataDir);
        //    //TransactedIO.Write(JsonConvert.SerializeObject(Config.Instance), "settings.json", Config.DataDir);
        //    //File.WriteAllText(Path.Combine(Config.DataDir, "settings.json"), JsonConvert.SerializeObject(Config.Instance));
        //}
    }

    public enum FolderSelectionMode
    {
        Auto, Manual
    }

    public enum FileConflictResolution
    {
        AutoRename,
        Overwrite
    }
}
