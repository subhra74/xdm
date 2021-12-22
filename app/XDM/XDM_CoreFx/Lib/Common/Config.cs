using Newtonsoft.Json;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using TraceLog;
using XDM.Core.Lib.Util;

namespace XDM.Core.Lib.Common
{
    public class Config
    {
        public static Config Instance { get; private set; }

        public static string DataDir { get; set; }

        public bool IsBrowserMonitoringEnabled { get; set; } = true;

        public static string[] DefaultVideoExtensions => new string[]
            {
                "MP4", "M3U8", "F4M", "WEBM", "OGG", "MP3", "AAC", "FLV", "MKV", "DIVX",
                "MOV", "MPG", "MPEG","OPUS"
            };

        public string[] VideoExtensions { get; set; }

        public static string[] DefaultFileExtensions => new string[]
            {
                "3GP", "7Z", "AVI", "BZ2", "DEB", "DOC", "DOCX", "EXE", "GZ", "ISO",
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

        [JsonIgnore]
        public bool ShutdownAfterAllFinished { get; set; } = false;

        public bool KeepPCAwake { get; set; } = true;

        public bool RunCommandAfterCompletion { get; set; } = false;

        public string AfterCompletionCommand { get; set; }

        public bool ScanWithAntiVirus { get; set; } = false;

        public string AntiVirusExecutable { get; set; }

        public string AntiVirusArgs { get; set; }

        public ProxyInfo? Proxy { get; set; }

        [JsonIgnore]
        public bool RunOnLogon
        {
            get => Helpers.IsAutoStartEnabled();
            set => Helpers.EnableAutoStart(value);
        }

        public string DefaultDownloadFolder { get; set; } =
            Helpers.GetOsDefaultDownloadFolder();

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
                DefaultFolder=Path.Combine(Helpers.GetOsDefaultDownloadFolder(),
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
                DefaultFolder=Path.Combine(Helpers.GetOsDefaultDownloadFolder(),"Music"),
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
                DefaultFolder=Path.Combine(Helpers.GetOsDefaultDownloadFolder(),"Video"),
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
                DefaultFolder=Path.Combine(Helpers.GetOsDefaultDownloadFolder(),"Compressed"),
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
                DefaultFolder=Path.Combine(Helpers.GetOsDefaultDownloadFolder(),"Programs"),
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

        public static void LoadConfig()
        {
            Instance = new Config
            {
                TempDir = Path.Combine(Config.DataDir, "temp")
            };
            var bytes = TransactedIO.ReadBytes("settings.db", Config.DataDir);
            if (bytes != null)
            {
                using var ms = new MemoryStream(bytes);
                using var reader = new BinaryReader(ms);
                PopulateConfig(Instance, reader);
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

        private const byte INT = 0, STRING = 1, BOOL = 2, STRING_ARRAY = 3, INT_ARRAY = 4, OBJECT_ARRAY = 5;

        private static void SkipUnknownField(byte type, string name, BinaryReader r)
        {
            Log.Debug($"Config skipping unknown field '{name}' of type '{type}'");
            switch (type)
            {
                case INT:
                    r.ReadInt32();
                    break;
                case STRING:
                    r.ReadString();
                    break;
                case BOOL:
                    r.ReadBoolean();
                    break;
                case STRING_ARRAY:
                    var sc = r.ReadInt16();
                    for (int i = 0; i < sc; i++)
                    {
                        r.ReadString();
                    }
                    break;
                case INT_ARRAY:
                    var ic = r.ReadInt16();
                    for (int i = 0; i < ic; i++)
                    {
                        r.ReadInt32();
                    }
                    break;
                case OBJECT_ARRAY:
                    var oc = r.ReadInt16();
                    for (int i = 0; i < oc; i++)
                    {
                        var name1 = r.ReadString();
                        var type1 = r.ReadByte();
                        SkipUnknownField(type1, name1, r);
                    }
                    break;
                default:
                    throw new IOException($"Unknown field type '{type}'");
            }
        }

        private static void PopulateConfig2(Config instance, BinaryReader r)
        {
            var fieldsCount = r.ReadInt16();
            for (var i = 0; i < fieldsCount; i++)
            {
                var fieldName = r.ReadString();
                var fieldType = r.ReadByte();
                switch (fieldName)
                {
                    case "AfterCompletionCommand":
                        instance.AfterCompletionCommand = r.ReadString();
                        break;
                    case "AntiVirusArgs":
                        instance.AntiVirusArgs = r.ReadString();
                        break;
                    case "AntiVirusExecutable":
                        instance.AntiVirusExecutable = r.ReadString();
                        break;
                    case "BlockedHosts":
                        var blockedHostsLength = r.ReadInt16();
                        instance.BlockedHosts = new string[blockedHostsLength];
                        for (int a = 0; a < blockedHostsLength; a++)
                        {
                            instance.BlockedHosts[i] = r.ReadString();
                        }
                        break;
                    case "Categories":
                        var categoriessLength = r.ReadInt16();
                        var categories = new List<Category>(categoriessLength);
                        for (int a = 0; a < categoriessLength; a++)
                        {
                            var cat = new Category();
                            var fieldName1 = r.ReadString();
                            var fieldType1 = r.ReadByte();
                            switch (fieldName1)
                            {
                                case "DefaultFolder":
                                    cat.DefaultFolder = r.ReadString();
                                    break;
                                case "DisplayName":
                                    cat.DisplayName = r.ReadString();
                                    break;
                                case "IsPredefined":
                                    cat.IsPredefined = r.ReadBoolean();
                                    break;
                                case "Name":
                                    cat.Name = r.ReadString();
                                    break;
                                case "FileExtensions":
                                    cat.FileExtensions = new HashSet<string>();
                                    var fileExtensionsLength1 = r.ReadInt16();
                                    for (int m = 0; m < fileExtensionsLength1; m++)
                                    {
                                        cat.FileExtensions.Add(r.ReadString());
                                    }
                                    break;
                                default:
                                    SkipUnknownField(fieldType1, fieldName1, r);
                                    break;
                            }
                            categories.Add(cat);
                            instance.Categories = categories;
                        }
                        break;
                    case "DefaultDownloadFolder":
                        instance.DefaultDownloadFolder = r.ReadString();
                        break;
                    case "EnableSpeedLimit":
                        instance.EnableSpeedLimit = r.ReadBoolean();
                        break;
                    case "FetchServerTimeStamp":
                        instance.FetchServerTimeStamp = r.ReadBoolean();
                        break;
                    case "FileConflictResolution":
                        instance.FileConflictResolution = (FileConflictResolution)r.ReadInt32();
                        break;
                    case "FolderSelectionMode":
                        instance.FolderSelectionMode = (FolderSelectionMode)r.ReadInt32();
                        break;
                    case "DefaltDownloadSpeed":
                        instance.DefaltDownloadSpeed = r.ReadInt32();
                        break;
                    case "IsBrowserMonitoringEnabled":
                        instance.IsBrowserMonitoringEnabled = r.ReadBoolean();
                        break;
                    case "KeepPCAwake":
                        instance.KeepPCAwake = r.ReadBoolean();
                        break;
                    case "Language":
                        instance.Language = r.ReadString();
                        break;
                    case "MaxParallelDownloads":
                        instance.MaxParallelDownloads = r.ReadInt32();
                        break;
                    case "MaxRetry":
                        instance.MaxRetry = r.ReadInt32();
                        break;
                    case "MaxSegments":
                        instance.MaxSegments = r.ReadInt32();
                        break;
                    case "MinVideoSize":
                        instance.MinVideoSize = r.ReadInt32();
                        break;
                    case "MonitorClipboard":
                        instance.MonitorClipboard = r.ReadBoolean();
                        break;
                    case "NetworkTimeout":
                        instance.NetworkTimeout = r.ReadInt32();
                        break;
                    case "RetryDelay":
                        instance.RetryDelay = r.ReadInt32();
                        break;
                    case "RunCommandAfterCompletion":
                        instance.RunCommandAfterCompletion = r.ReadBoolean();
                        break;
                    case "RunOnLogon":
                        instance.RunOnLogon = r.ReadBoolean();
                        break;
                    case "ScanWithAntiVirus":
                        instance.ScanWithAntiVirus = r.ReadBoolean();
                        break;
                    case "ShowDownloadCompleteWindow":
                        instance.ShowDownloadCompleteWindow = r.ReadBoolean();
                        break;
                    case "ShowProgressWindow":
                        instance.ShowProgressWindow = r.ReadBoolean();
                        break;
                    case "ShutdownAfterAllFinished":
                        instance.ShutdownAfterAllFinished = r.ReadBoolean();
                        break;
                    case "StartDownloadAutomatically":
                        instance.StartDownloadAutomatically = r.ReadBoolean();
                        break;
                    case "TempDir":
                        instance.TempDir = r.ReadString();
                        break;
                    case "AllowSystemDarkTheme":
                        instance.AllowSystemDarkTheme = r.ReadBoolean();
                        break;
                    case "FileExtensions":
                        var fileExtensionsLength = r.ReadInt16();
                        instance.FileExtensions = new string[fileExtensionsLength];
                        for (int a = 0; a < fileExtensionsLength; a++)
                        {
                            instance.FileExtensions[i] = r.ReadString();
                        }
                        break;
                    case "RecentFolders":
                        var recentFoldersLength = r.ReadInt16();
                        instance.RecentFolders = new List<string>(recentFoldersLength);
                        for (int a = 0; a < recentFoldersLength; a++)
                        {
                            instance.RecentFolders.Add(r.ReadString());
                        }
                        break;
                    case "VideoExtensions":
                        var videoExtensionsLength = r.ReadInt16();
                        instance.VideoExtensions = new string[videoExtensionsLength];
                        for (int a = 0; a < videoExtensionsLength; a++)
                        {
                            instance.VideoExtensions[i] = r.ReadString();
                        }
                        break;
                    case "UserCredentials":
                        var userCredentialsLength = r.ReadInt16();
                        var passwordEntries = new List<PasswordEntry>(userCredentialsLength);
                        for (int a = 0; a < userCredentialsLength; a++)
                        {
                            var passwordEntry = new PasswordEntry();
                            var fieldName1 = r.ReadString();
                            var fieldType1 = r.ReadByte();
                            switch (fieldName1)
                            {
                                case "Host":
                                    passwordEntry.Host = r.ReadString();
                                    break;
                                case "User":
                                    passwordEntry.User = r.ReadString();
                                    break;
                                case "Password":
                                    passwordEntry.Password = r.ReadString();
                                    break;
                                default:
                                    SkipUnknownField(fieldType1, fieldName1, r);
                                    break;
                            }
                            passwordEntries.Add(passwordEntry);
                            instance.UserCredentials = passwordEntries;
                        }
                        break;
                    case "Proxy":
                        instance.Proxy = ProxyInfoSerializer.Deserialize(r);
                        break;
                    default:
                        SkipUnknownField(fieldType, fieldName, r);
                        break;
                }
            }
        }

        private static void PopulateConfig(Config instance, BinaryReader r)
        {
            instance.AfterCompletionCommand = Helpers.ReadString(r);
            instance.AntiVirusArgs = Helpers.ReadString(r);
            instance.AntiVirusExecutable = Helpers.ReadString(r);
            var count = r.ReadInt32();
            instance.BlockedHosts = new string[count];
            for (int i = 0; i < count; i++)
            {
                instance.BlockedHosts[i] = r.ReadString();
            }
            count = r.ReadInt32();
            var list = new List<Category>(count);
            for (int i = 0; i < count; i++)
            {
                var category = new Category
                {
                    DefaultFolder = Helpers.ReadString(r),
                    DisplayName = Helpers.ReadString(r),
                    FileExtensions = new HashSet<string>(),
                };
                var c2 = r.ReadInt32();
                for (int j = 0; j < c2; j++)
                {
                    category.FileExtensions.Add(r.ReadString());
                }
                category.IsPredefined = r.ReadBoolean();
                category.Name = r.ReadString();
                list.Add(category);
            }
            instance.Categories = list;
            instance.DefaultDownloadFolder = Helpers.ReadString(r);
            instance.EnableSpeedLimit = r.ReadBoolean();
            instance.FetchServerTimeStamp = r.ReadBoolean();
            instance.FileConflictResolution = (FileConflictResolution)r.ReadInt32();
            count = r.ReadInt32();
            instance.FileExtensions = new string[count];
            for (int i = 0; i < count; i++)
            {
                instance.FileExtensions[i] = r.ReadString();
            }
            instance.FolderSelectionMode = (FolderSelectionMode)r.ReadInt32();
            instance.DefaltDownloadSpeed = r.ReadInt32();
            instance.IsBrowserMonitoringEnabled = r.ReadBoolean();
            instance.KeepPCAwake = r.ReadBoolean();
            instance.Language = r.ReadString();
            instance.MaxParallelDownloads = r.ReadInt32();
            instance.MaxRetry = r.ReadInt32();
            instance.MaxSegments = r.ReadInt32();
            instance.MinVideoSize = r.ReadInt32();
            instance.MonitorClipboard = r.ReadBoolean();
            instance.NetworkTimeout = r.ReadInt32();
            count = r.ReadInt32();
            instance.RecentFolders = new List<string>(count);
            for (int i = 0; i < count; i++)
            {
                instance.RecentFolders.Add(r.ReadString());
            }
            instance.RetryDelay = r.ReadInt32();
            instance.RunCommandAfterCompletion = r.ReadBoolean();
            instance.RunOnLogon = r.ReadBoolean();
            instance.ScanWithAntiVirus = r.ReadBoolean();
            instance.ShowDownloadCompleteWindow = r.ReadBoolean();
            instance.ShowProgressWindow = r.ReadBoolean();
            instance.ShutdownAfterAllFinished = r.ReadBoolean();
            instance.StartDownloadAutomatically = r.ReadBoolean();
            instance.TempDir = Helpers.ReadString(r);
            count = r.ReadInt32();
            var list2 = new List<PasswordEntry>(count);
            for (int i = 0; i < count; i++)
            {
                var passwordEntry = new PasswordEntry
                {
                    Host = Helpers.ReadString(r),
                    User = Helpers.ReadString(r),
                    Password = Helpers.ReadString(r)
                };
                list2.Add(passwordEntry);
            }
            instance.UserCredentials = list2;
            count = r.ReadInt32();
            instance.VideoExtensions = new string[count];
            for (int i = 0; i < count; i++)
            {
                instance.VideoExtensions[i] = r.ReadString();
            }
            instance.Proxy = ProxyInfoSerializer.Deserialize(r);
            instance.AllowSystemDarkTheme = r.ReadBoolean();
        }

        public static void SaveConfig()
        {
            using var ms = new MemoryStream();
            using var writer = new BinaryWriter(ms);
            writer.Write(Instance.AfterCompletionCommand ?? string.Empty);
            writer.Write(Instance.AntiVirusArgs ?? string.Empty);
            writer.Write(Instance.AntiVirusExecutable ?? string.Empty);
            var count = Instance.BlockedHosts?.Length ?? 0;
            writer.Write(count);
            for (int i = 0; i < count; i++)
            {
                writer.Write(Instance.BlockedHosts![i]);
            }
            count = Instance.Categories.Count();
            writer.Write(count);
            foreach (var category in Instance.Categories)
            {
                writer.Write(category.DefaultFolder);
                writer.Write(category.DisplayName ?? string.Empty);
                count = category.FileExtensions.Count();
                writer.Write(count);
                foreach (var ext in category.FileExtensions)
                {
                    writer.Write(ext);
                }
                writer.Write(category.IsPredefined);
                writer.Write(category.Name);
            }
            writer.Write(Instance.DefaultDownloadFolder ?? string.Empty);
            writer.Write(Instance.EnableSpeedLimit);
            writer.Write(Instance.FetchServerTimeStamp);
            writer.Write((int)Instance.FileConflictResolution);
            count = Instance.FileExtensions.Length;
            writer.Write(count);
            foreach (var ext in Instance.FileExtensions)
            {
                writer.Write(ext);
            }
            writer.Write((int)Instance.FolderSelectionMode);
            writer.Write(Instance.DefaltDownloadSpeed);
            writer.Write(Instance.IsBrowserMonitoringEnabled);
            writer.Write(Instance.KeepPCAwake);
            writer.Write(Instance.Language);
            writer.Write(Instance.MaxParallelDownloads);
            writer.Write(Instance.MaxRetry);
            writer.Write(Instance.MaxSegments);
            writer.Write(Instance.MinVideoSize);
            writer.Write(Instance.MonitorClipboard);
            writer.Write(Instance.NetworkTimeout);
            count = Instance.RecentFolders.Count;
            writer.Write(count);
            foreach (var recentFolder in Instance.RecentFolders)
            {
                writer.Write(recentFolder);
            }
            writer.Write(Instance.RetryDelay);
            writer.Write(Instance.RunCommandAfterCompletion);
            writer.Write(Instance.RunOnLogon);
            writer.Write(Instance.ScanWithAntiVirus);
            writer.Write(Instance.ShowDownloadCompleteWindow);
            writer.Write(Instance.ShowProgressWindow);
            writer.Write(Instance.ShutdownAfterAllFinished);
            writer.Write(Instance.StartDownloadAutomatically);
            writer.Write(Instance.TempDir);
            count = Instance.UserCredentials.Count();
            writer.Write(count);
            foreach (var pe in Instance.UserCredentials)
            {
                writer.Write(pe.Host ?? string.Empty);
                writer.Write(pe.User ?? string.Empty);
                writer.Write(pe.Password ?? string.Empty);
            }
            count = Instance.VideoExtensions.Length;
            writer.Write(count);
            foreach (var ext in Instance.VideoExtensions)
            {
                writer.Write(ext);
            }
            ProxyInfoSerializer.Serialize(Instance.Proxy, writer);
            writer.Write(Instance.AllowSystemDarkTheme);
            writer.Close();
            ms.Close();
            TransactedIO.WriteBytes(ms.ToArray(), "settings.db", Config.DataDir);
            //TransactedIO.Write(JsonConvert.SerializeObject(Config.Instance), "settings.json", Config.DataDir);
            //File.WriteAllText(Path.Combine(Config.DataDir, "settings.json"), JsonConvert.SerializeObject(Config.Instance));
        }
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
