using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using TraceLog;
using XDM.Core.Lib.Common;

namespace XDM.Core.Lib.Util
{
    internal static class SerializationHelper
    {
        public const byte INT = 0, STRING = 1, BOOL = 2, STRING_ARRAY = 3, INT_ARRAY = 4, OBJECT_ARRAY = 5, LONG = 6, OBJECT = 7;

        public static void SkipUnknownField(byte type, string name, BinaryReader r)
        {
            Log.Debug($"Config skipping unknown field '{name}' of type '{type}'");
            switch (type)
            {
                case INT:
                    r.ReadInt32();
                    break;
                case LONG:
                    r.ReadInt64();
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
                    var fc = r.ReadInt16();
                    for (int i = 0; i < oc; i++)
                    {
                        for (int j = 0; j < fc; j++)
                        {
                            var name1 = r.ReadString();
                            var type1 = r.ReadByte();
                            SkipUnknownField(type1, name1, r);
                        }
                    }
                    break;
                case OBJECT:
                    var fc1 = r.ReadInt16();
                    for (int j = 0; j < fc1; j++)
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

        public static void DeserializeConfig(Config instance, BinaryReader r)
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
                            instance.BlockedHosts[a] = r.ReadString();
                        }
                        break;
                    case "Categories":
                        var categoriesLength = r.ReadInt16();
                        var categoriesFieldCount = r.ReadInt16();
                        var categories = new List<Category>(categoriesLength);
                        for (int a = 0; a < categoriesLength; a++)
                        {
                            var cat = new Category();
                            for (int b = 0; b < categoriesFieldCount; b++)
                            {
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
                            instance.FileExtensions[a] = r.ReadString();
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
                            instance.VideoExtensions[a] = r.ReadString();
                        }
                        break;
                    case "UserCredentials":
                        var userCredentialsLength = r.ReadInt16();
                        var passwordEntryFieldLength = r.ReadInt16();
                        var passwordEntries = new List<PasswordEntry>(userCredentialsLength);
                        for (int a = 0; a < userCredentialsLength; a++)
                        {
                            var passwordEntry = new PasswordEntry();
                            for (int b = 0; b < passwordEntryFieldLength; b++)
                            {
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

        private static void WriteString(BinaryWriter w, string value, string name)
        {
            w.Write(name);
            w.Write(STRING);
            w.Write(value ?? string.Empty);
        }

        private static void WriteBoolean(BinaryWriter w, bool value, string name)
        {
            w.Write(name);
            w.Write(BOOL);
            w.Write(value);
        }

        private static void WriteInt32(BinaryWriter w, int value, string name)
        {
            w.Write(name);
            w.Write(INT);
            w.Write(value);
        }

        private static void WriteInt64(BinaryWriter w, long value, string name)
        {
            w.Write(name);
            w.Write(LONG);
            w.Write(value);
        }

        private static void WriteStringArray(BinaryWriter w, IEnumerable<string> array, string name, int count)
        {
            w.Write(name);
            w.Write(STRING_ARRAY);
            w.Write((short)count);
            foreach (var item in array)
            {
                w.Write(item);
            }
        }

        public static void SerializeConfig()
        {
            var instance = Config.Instance;
            using var ms = new MemoryStream();
            using var w = new BinaryWriter(ms);

            w.Write((short)(instance.Proxy.HasValue ? 35 : 34)); //total fields

            WriteString(w, instance.AfterCompletionCommand, "AfterCompletionCommand");
            WriteString(w, instance.AntiVirusArgs, "AntiVirusArgs");
            WriteString(w, instance.AntiVirusExecutable, "AntiVirusExecutable");
            WriteString(w, instance.DefaultDownloadFolder, "DefaultDownloadFolder");
            WriteString(w, instance.Language, "Language");
            WriteString(w, instance.TempDir, "TempDir");

            WriteBoolean(w, instance.EnableSpeedLimit, "EnableSpeedLimit");
            WriteBoolean(w, instance.FetchServerTimeStamp, "FetchServerTimeStamp");
            WriteBoolean(w, instance.IsBrowserMonitoringEnabled, "IsBrowserMonitoringEnabled");
            WriteBoolean(w, instance.KeepPCAwake, "KeepPCAwake");
            WriteBoolean(w, instance.MonitorClipboard, "MonitorClipboard");
            WriteBoolean(w, instance.RunCommandAfterCompletion, "RunCommandAfterCompletion");
            WriteBoolean(w, instance.RunOnLogon, "RunOnLogon");
            WriteBoolean(w, instance.ScanWithAntiVirus, "ScanWithAntiVirus");
            WriteBoolean(w, instance.ShowDownloadCompleteWindow, "ShowDownloadCompleteWindow");
            WriteBoolean(w, instance.ShowProgressWindow, "ShowProgressWindow");
            WriteBoolean(w, instance.ShutdownAfterAllFinished, "ShutdownAfterAllFinished");
            WriteBoolean(w, instance.StartDownloadAutomatically, "StartDownloadAutomatically");
            WriteBoolean(w, instance.AllowSystemDarkTheme, "AllowSystemDarkTheme");

            WriteInt32(w, (int)instance.FileConflictResolution, "FileConflictResolution");
            WriteInt32(w, (int)instance.FolderSelectionMode, "FolderSelectionMode");

            WriteInt32(w, instance.DefaltDownloadSpeed, "DefaltDownloadSpeed");
            WriteInt32(w, instance.MaxParallelDownloads, "MaxParallelDownloads");
            WriteInt32(w, instance.MaxRetry, "MaxRetry");
            WriteInt32(w, instance.MaxSegments, "MaxSegments");
            WriteInt32(w, instance.MinVideoSize, "MinVideoSize");
            WriteInt32(w, instance.NetworkTimeout, "NetworkTimeout");
            WriteInt32(w, instance.RetryDelay, "RetryDelay");

            WriteStringArray(w, instance.BlockedHosts, "BlockedHosts", instance.BlockedHosts.Length);
            WriteStringArray(w, instance.FileExtensions, "FileExtensions", instance.FileExtensions.Length);
            WriteStringArray(w, instance.RecentFolders, "RecentFolders", instance.RecentFolders.Count);
            WriteStringArray(w, instance.VideoExtensions, "VideoExtensions", instance.VideoExtensions.Length);

            w.Write("Categories");
            w.Write(OBJECT_ARRAY);
            w.Write((short)instance.Categories.Count());
            w.Write((short)5); //no of fields in Category class
            foreach (var cat in instance.Categories)
            {
                WriteString(w, cat.DefaultFolder, "DefaultFolder");
                WriteString(w, cat.DisplayName, "DisplayName");
                WriteBoolean(w, cat.IsPredefined, "IsPredefined");
                WriteString(w, cat.Name, "Name");
                WriteStringArray(w, cat.FileExtensions, "FileExtensions", cat.FileExtensions.Count);
            }

            w.Write("UserCredentials");
            w.Write(OBJECT_ARRAY);
            w.Write((short)instance.UserCredentials.Count());
            w.Write((short)3); //no of fields in Category class
            foreach (var pe in instance.UserCredentials)
            {
                WriteString(w, pe.Host, "Host");
                WriteString(w, pe.User, "User");
                WriteString(w, pe.Password, "Password");
            }

            if (instance.Proxy.HasValue)
            {
                ProxyInfoSerializer.Serialize(instance.Proxy.Value, w);
            }

            w.Close();
            ms.Close();
            TransactedIO.WriteBytes(ms.ToArray(), "settings.dat", Config.DataDir);
        }

        public static void SerializeProxyInfo(ProxyInfo proxy, BinaryWriter w)
        {
            w.Write("Proxy");
            w.Write(OBJECT);
            w.Write((short)5);
            w.Write(nameof(proxy.Host));
            w.Write(STRING);
            w.Write(proxy.Host);
            w.Write(nameof(proxy.Port));
            w.Write(INT);
            w.Write(proxy.Port);
            w.Write(nameof(proxy.ProxyType));
            w.Write(INT);
            w.Write((int)proxy.ProxyType);
            w.Write(nameof(proxy.UserName));
            w.Write(STRING);
            w.Write(proxy.UserName);
            w.Write(nameof(proxy.Password));
            w.Write(STRING);
            w.Write(proxy.Password ?? string.Empty);
        }

        public static ProxyInfo DeserializeProxyInfo(BinaryReader r)
        {
            var proxy = new ProxyInfo();
            var fieldCount = r.ReadInt16();
            for (int i = 0; i < fieldCount; i++)
            {
                var fieldName = r.ReadString();
                var fieldType = r.ReadByte();
                switch (fieldName)
                {
                    case "Host":
                        proxy.Host = r.ReadString();
                        break;
                    case "Port":
                        proxy.Port = r.ReadInt32();
                        break;
                    case "ProxyType":
                        proxy.ProxyType = (ProxyType)r.ReadInt32();
                        break;
                    case "UserName":
                        proxy.UserName = r.ReadString();
                        break;
                    case "Password":
                        proxy.Password = r.ReadString();
                        break;
                    default:
                        switch (fieldType)
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
                            default:
                                throw new IOException($"Unsupported type: '{fieldType}'");
                        }
                        break;
                }
            }
            return proxy;
        }

        public static List<InProgressDownloadEntry> DeserializeInProgressDownloadEntry(BinaryReader r)
        {
            var count = r.ReadInt32();
            var list = new List<InProgressDownloadEntry>(count);
            for (int i = 0; i < count; i++)
            {
                var instance = new InProgressDownloadEntry();
                var fieldsCount = r.ReadInt16();
                for (var j = 0; j < fieldsCount; j++)
                {
                    var fieldName = r.ReadString();
                    var fieldType = r.ReadByte();
                    switch (fieldName)
                    {
                        case "Id":
                            instance.Id = r.ReadString();
                            break;
                        case "Name":
                            instance.Name = r.ReadString();
                            break;
                        case "DateAdded":
                            instance.DateAdded = DateTime.FromBinary(r.ReadInt64());
                            break;
                        case "DownloadType":
                            instance.DownloadType = r.ReadString();
                            break;
                        case "FileNameFetchMode":
                            instance.FileNameFetchMode = (FileNameFetchMode)r.ReadInt32();
                            break;
                        case "MaxSpeedLimitInKiB":
                            instance.MaxSpeedLimitInKiB = r.ReadInt32();
                            break;
                        case "Progress":
                            instance.Progress = r.ReadInt32();
                            break;
                        case "Size":
                            instance.Size = r.ReadInt64();
                            break;
                        case "PrimaryUrl":
                            instance.PrimaryUrl = r.ReadString();
                            break;
                        case "TargetDir":
                            instance.TargetDir = r.ReadString();
                            break;
                        case "RefererUrl":
                            instance.RefererUrl = r.ReadString();
                            break;
                        case "Authentication":
                            var authenticationInfoFieldLength = r.ReadInt16();
                            var authenticationInfo = new AuthenticationInfo();
                            for (int b = 0; b < authenticationInfoFieldLength; b++)
                            {
                                var fieldName1 = r.ReadString();
                                var fieldType1 = r.ReadByte();
                                switch (fieldName1)
                                {
                                    case "User":
                                        authenticationInfo.UserName = r.ReadString();
                                        break;
                                    case "Password":
                                        authenticationInfo.Password = r.ReadString();
                                        break;
                                    default:
                                        SkipUnknownField(fieldType1, fieldName1, r);
                                        break;
                                }
                            }
                            break;
                        case "Proxy":
                            instance.Proxy = DeserializeProxyInfo(r);
                            break;
                        default:
                            SkipUnknownField(fieldType, fieldName, r);
                            break;
                    }
                }
                instance.Status = DownloadStatus.Stopped;
                list.Add(instance);
            }
            return list;
        }

        public static List<FinishedDownloadEntry> DeserializeFinishedDownloadEntry(BinaryReader r)
        {
            var count = r.ReadInt32();
            var list = new List<FinishedDownloadEntry>(count);
            for (int i = 0; i < count; i++)
            {
                var instance = new FinishedDownloadEntry();
                var fieldsCount = r.ReadInt16();
                for (var j = 0; j < fieldsCount; j++)
                {
                    var fieldName = r.ReadString();
                    var fieldType = r.ReadByte();
                    switch (fieldName)
                    {
                        case "Id":
                            instance.Id = r.ReadString();
                            break;
                        case "Name":
                            instance.Name = r.ReadString();
                            break;
                        case "DateAdded":
                            instance.DateAdded = DateTime.FromBinary(r.ReadInt64());
                            break;
                        case "DownloadType":
                            instance.DownloadType = r.ReadString();
                            break;
                        case "FileNameFetchMode":
                            instance.FileNameFetchMode = (FileNameFetchMode)r.ReadInt32();
                            break;
                        case "MaxSpeedLimitInKiB":
                            instance.MaxSpeedLimitInKiB = r.ReadInt32();
                            break;
                        case "Size":
                            instance.Size = r.ReadInt64();
                            break;
                        case "PrimaryUrl":
                            instance.PrimaryUrl = r.ReadString();
                            break;
                        case "TargetDir":
                            instance.TargetDir = r.ReadString();
                            break;
                        case "RefererUrl":
                            instance.RefererUrl = r.ReadString();
                            break;
                        case "Authentication":
                            var authenticationInfoFieldLength = r.ReadInt16();
                            var authenticationInfo = new AuthenticationInfo();
                            for (int b = 0; b < authenticationInfoFieldLength; b++)
                            {
                                var fieldName1 = r.ReadString();
                                var fieldType1 = r.ReadByte();
                                switch (fieldName1)
                                {
                                    case "User":
                                        authenticationInfo.UserName = r.ReadString();
                                        break;
                                    case "Password":
                                        authenticationInfo.Password = r.ReadString();
                                        break;
                                    default:
                                        SkipUnknownField(fieldType1, fieldName1, r);
                                        break;
                                }
                            }
                            break;
                        case "Proxy":
                            instance.Proxy = DeserializeProxyInfo(r);
                            break;
                        default:
                            SkipUnknownField(fieldType, fieldName, r);
                            break;
                    }
                }
                list.Add(instance);
            }
            return list;
        }

        public static void SerializeInProgressDownloadEntry(BinaryWriter w, List<InProgressDownloadEntry> list)
        {
            w.Write(list.Count);

            for (int i = 0; i < list.Count; i++)
            {
                var fieldCount = 8;
                var ent = list[i];
                if (!string.IsNullOrEmpty(ent.PrimaryUrl))
                {
                    fieldCount++;
                }
                if (!string.IsNullOrEmpty(ent.TargetDir))
                {
                    fieldCount++;
                }
                if (!string.IsNullOrEmpty(ent.RefererUrl))
                {
                    fieldCount++;
                }
                if (ent.Authentication != null)
                {
                    fieldCount++;
                }
                if (ent.Proxy != null)
                {
                    fieldCount++;
                }
                w.Write((short)fieldCount);
                WriteString(w, ent.Id, "Id");
                WriteString(w, ent.Name, "Name");
                WriteInt64(w, ent.DateAdded.ToBinary(), "DateAdded");
                WriteString(w, ent.DownloadType, "DownloadType");
                WriteInt32(w, (int)ent.FileNameFetchMode, "FileNameFetchMode");
                WriteInt32(w, ent.MaxSpeedLimitInKiB, "MaxSpeedLimitInKiB");
                WriteInt32(w, ent.Progress, "Progress");
                WriteInt64(w, ent.Size, "Size");
                if (!string.IsNullOrEmpty(ent.PrimaryUrl))
                {
                    WriteString(w, ent.PrimaryUrl, "PrimaryUrl");
                }
                if (!string.IsNullOrEmpty(ent.TargetDir))
                {
                    WriteString(w, ent.TargetDir, "TargetDir");
                }
                if (!string.IsNullOrEmpty(ent.RefererUrl))
                {
                    WriteString(w, ent.RefererUrl, "RefererUrl");
                }
                if (ent.Authentication != null)
                {
                    w.Write("Authentication");
                    w.Write(OBJECT);
                    w.Write((short)2); //no of fields in Authentication class
                    WriteString(w, ent.Authentication.Value.UserName, "User");
                    WriteString(w, ent.Authentication.Value.Password, "Password");
                }
                if (ent.Proxy != null)
                {
                    ProxyInfoSerializer.Serialize(ent.Proxy.Value, w);
                }
            }
        }

        public static void SerializeFinishedDownloadEntry(BinaryWriter w, List<FinishedDownloadEntry> list)
        {
            w.Write(list.Count);

            for (int i = 0; i < list.Count; i++)
            {
                var fieldCount = 7;
                var ent = list[i];
                if (!string.IsNullOrEmpty(ent.PrimaryUrl))
                {
                    fieldCount++;
                }
                if (!string.IsNullOrEmpty(ent.TargetDir))
                {
                    fieldCount++;
                }
                if (!string.IsNullOrEmpty(ent.RefererUrl))
                {
                    fieldCount++;
                }
                if (ent.Authentication != null)
                {
                    fieldCount++;
                }
                if (ent.Proxy != null)
                {
                    fieldCount++;
                }
                w.Write((short)fieldCount);
                WriteString(w, ent.Id, "Id");
                WriteString(w, ent.Name, "Name");
                WriteInt64(w, ent.DateAdded.ToBinary(), "DateAdded");
                WriteString(w, ent.DownloadType, "DownloadType");
                WriteInt32(w, (int)ent.FileNameFetchMode, "FileNameFetchMode");
                WriteInt32(w, ent.MaxSpeedLimitInKiB, "MaxSpeedLimitInKiB");
                WriteInt64(w, ent.Size, "Size");
                if (!string.IsNullOrEmpty(ent.PrimaryUrl))
                {
                    WriteString(w, ent.PrimaryUrl, "PrimaryUrl");
                }
                if (!string.IsNullOrEmpty(ent.TargetDir))
                {
                    WriteString(w, ent.TargetDir, "TargetDir");
                }
                if (!string.IsNullOrEmpty(ent.RefererUrl))
                {
                    WriteString(w, ent.RefererUrl, "RefererUrl");
                }
                if (ent.Authentication != null)
                {
                    w.Write("Authentication");
                    w.Write(OBJECT);
                    w.Write((short)2); //no of fields in Authentication class
                    WriteString(w, ent.Authentication.Value.UserName, "User");
                    WriteString(w, ent.Authentication.Value.Password, "Password");
                }
                if (ent.Proxy != null)
                {
                    ProxyInfoSerializer.Serialize(ent.Proxy.Value, w);
                }
            }
        }
    }
}
