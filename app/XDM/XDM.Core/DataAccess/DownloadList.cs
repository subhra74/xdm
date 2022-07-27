using System;
using System.Collections.Generic;
using System.Data.Common;
using System.Data.SQLite;
using System.IO;
using System.Linq;
using System.Text;
using TraceLog;
using XDM.Core;
using XDM.Core.DataAccess.Extensions;
using XDM.Core.Downloader;

namespace XDM.Core.DataAccess
{
    public class DownloadList
    {
        private SQLiteConnection db;

        public DownloadList(SQLiteConnection db)
        {
            this.db = db;
        }

        private SQLiteCommand cmdFetchAll, cmdFetchConditional, cmdFetchOne, cmdUpdateProgress, cmdUpdateTargetDir,
            cmdInsertOne, cmdMarkFinished, cmdUpdateStatus, cmdUpdateNameAndSize, cmdUpdateNameAndFolder, cmdUpdateOne, cmdDelete;

        public bool LoadDownloads(
            out List<InProgressDownloadItem> inProgressDownloads,
            out List<FinishedDownloadItem> finishedDownloads, QueryMode queryMode = QueryMode.All)
        {
            lock (db)
            {
                inProgressDownloads = new List<InProgressDownloadItem>();
                finishedDownloads = new List<FinishedDownloadItem>();
                try
                {
                    SQLiteCommand sqlCommand;
                    if (queryMode == QueryMode.All)
                    {
                        if (cmdFetchAll == null)
                        {
                            cmdFetchAll = new SQLiteCommand("SELECT * FROM downloads", db);
                        }
                        sqlCommand = cmdFetchAll;
                    }
                    else
                    {
                        if (cmdFetchConditional == null)
                        {
                            cmdFetchConditional = new SQLiteCommand("SELECT * FROM downloads WHERE completed=@completed", db);
                        }
                        SetParam("@completed", queryMode == QueryMode.InProgress ? 0 : 1, cmdFetchConditional.Parameters);
                        sqlCommand = cmdFetchConditional;
                    }
                    using SQLiteDataReader r = sqlCommand.ExecuteReader();
                    while (r.Read())
                    {
                        var id = r.GetSafeString(0);
                        var inProgress = r.GetInt32(1) == 0;
                        DownloadItemBase entry = r.GetInt32(1) == 0 ? new InProgressDownloadItem() : new FinishedDownloadItem();
                        entry.Id = id;
                        entry.Name = r.GetSafeString(2);
                        entry.DateAdded = DateTime.FromBinary(r.GetInt64(3));
                        entry.Size = r.GetInt64(4);
                        entry.DownloadType = r.GetSafeString(7);
                        entry.FileNameFetchMode = (FileNameFetchMode)r.GetInt32(8);
                        entry.MaxSpeedLimitInKiB = r.GetInt32(9);
                        entry.TargetDir = r.GetSafeString(10);
                        entry.PrimaryUrl = r.GetSafeString(11);
                        entry.RefererUrl = r.GetSafeString(12);
                        if (r.GetInt32(13) == 1)
                        {
                            var user = r.GetSafeString(14);
                            var pass = r.GetSafeString(15);
                            if (user != null)
                            {
                                entry.Authentication = new AuthenticationInfo
                                {
                                    UserName = user,
                                    Password = pass
                                };
                            }
                        }
                        var proxy = new ProxyInfo { };
                        proxy.ProxyType = (ProxyType)r.GetInt32(16);
                        proxy.Host = r.GetSafeString(17);
                        proxy.Port = r.GetInt32(18);
                        proxy.UserName = r.GetSafeString(19);
                        proxy.Password = r.GetSafeString(20);
                        entry.Proxy = proxy;

                        if (inProgress)
                        {
                            var inp = (InProgressDownloadItem)entry;
                            inp.Status = DownloadStatus.Stopped;
                            inp.Progress = r.GetInt32(6);
                            inProgressDownloads.Add(inp);
                        }
                        else
                        {
                            finishedDownloads.Add((FinishedDownloadItem)entry);
                        }
                    }
                    return true;
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.Message);
                }
                return false;
            }
        }

        public DownloadItemBase? GetDownloadById(string id)
        {
            lock (db)
            {
                try
                {
                    if (cmdFetchOne == null)
                    {
                        cmdFetchOne = new SQLiteCommand("SELECT * FROM downloads WHERE id=@id", db);
                    }
                    SetParam("@id", id, cmdFetchOne.Parameters);
                    //cmdFetchOne.Parameters["@id"].Value = id;
                    using SQLiteDataReader r = cmdFetchOne.ExecuteReader();
                    if (r.Read())
                    {
                        var inProgress = r.GetInt32(1) == 0;
                        DownloadItemBase entry = r.GetInt32(1) == 0 ? new InProgressDownloadItem() : new FinishedDownloadItem();
                        entry.Id = id;
                        entry.Name = r.GetSafeString(2);
                        entry.DateAdded = DateTime.FromBinary(r.GetInt64(3));
                        entry.Size = r.GetInt64(4);
                        entry.DownloadType = r.GetSafeString(7);
                        entry.FileNameFetchMode = (FileNameFetchMode)r.GetInt32(8);
                        entry.MaxSpeedLimitInKiB = r.GetInt32(9);
                        entry.TargetDir = r.GetSafeString(10);
                        entry.PrimaryUrl = r.GetSafeString(11);
                        entry.RefererUrl = r.GetSafeString(12);
                        if (r.GetInt32(13) == 1)
                        {
                            var user = r.GetSafeString(14);
                            var pass = r.GetSafeString(15);
                            if (user != null)
                            {
                                entry.Authentication = new AuthenticationInfo
                                {
                                    UserName = user,
                                    Password = pass
                                };
                            }
                        }
                        var proxy = new ProxyInfo { };
                        proxy.ProxyType = (ProxyType)r.GetInt32(16);
                        proxy.Host = r.GetSafeString(17);
                        proxy.Port = r.GetInt32(18);
                        proxy.UserName = r.GetSafeString(19);
                        proxy.Password = r.GetSafeString(20);
                        entry.Proxy = proxy;

                        if (inProgress)
                        {
                            var inp = (InProgressDownloadItem)entry;
                            inp.Status = DownloadStatus.Stopped;
                            inp.Progress = r.GetInt32(6);
                        }
                        return entry;
                    }
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.Message);
                }
                return null;
            }
        }

        public bool AddNewDownload(InProgressDownloadItem entry)
        {
            lock (db)
            {
                try
                {
                    if (cmdInsertOne == null)
                    {
                        cmdInsertOne = new SQLiteCommand(@"INSERT INTO downloads(
                                            id, completed, name, date_added, size, status, 
                                            progress, download_type, filenamefetchmode, maxspeedlimitinkib, targetdir, primary_url,
                                            referer_url, auth, user, pass, proxy, proxy_host,
                                            proxy_port, proxy_user, proxy_pass, proxy_type)
                                            VALUES(
                                            @id, @completed, @name, @date_added, @size, @status, 
                                            @progress, @download_type, @filenamefetchmode, @maxspeedlimitinkib, @targetdir, @primary_url,
                                            @referer_url, @auth, @user, @pass, @proxy, @proxy_host, 
                                            @proxy_port, @proxy_user, @proxy_pass, @proxy_type)", db);
                    }
                    SetParam("@id", entry.Id, cmdInsertOne.Parameters);
                    SetParam("@completed", 0, cmdInsertOne.Parameters);
                    SetParam("@name", entry.Name, cmdInsertOne.Parameters);
                    SetParam("@date_added", entry.DateAdded.ToBinary(), cmdInsertOne.Parameters);
                    SetParam("@size", entry.Size, cmdInsertOne.Parameters);
                    SetParam("@status", (int)entry.Status, cmdInsertOne.Parameters);
                    SetParam("@progress", entry.Progress, cmdInsertOne.Parameters);
                    SetParam("@download_type", entry.DownloadType, cmdInsertOne.Parameters);
                    SetParam("@filenamefetchmode", (int)entry.FileNameFetchMode, cmdInsertOne.Parameters);
                    SetParam("@maxspeedlimitinkib", entry.MaxSpeedLimitInKiB, cmdInsertOne.Parameters);
                    SetParam("@targetdir", entry.TargetDir, cmdInsertOne.Parameters);
                    SetParam("@primary_url", entry.PrimaryUrl, cmdInsertOne.Parameters);
                    SetParam("@referer_url", entry.RefererUrl, cmdInsertOne.Parameters);
                    SetParam("@auth", entry.Authentication.HasValue ? 1 : 0, cmdInsertOne.Parameters);
                    SetParam("@user", entry.Authentication?.UserName ?? null, cmdInsertOne.Parameters);
                    SetParam("@pass", entry.Authentication?.Password ?? null, cmdInsertOne.Parameters);
                    SetParam("@proxy", (int)(entry.Proxy?.ProxyType ?? 0), cmdInsertOne.Parameters);
                    SetParam("@proxy_host", entry.Proxy?.Host ?? null, cmdInsertOne.Parameters);
                    SetParam("@proxy_port", (int)(entry.Proxy?.Port ?? 0), cmdInsertOne.Parameters);
                    SetParam("@proxy_user", entry.Proxy?.UserName ?? null, cmdInsertOne.Parameters);
                    SetParam("@proxy_pass", entry.Proxy?.Password ?? null, cmdInsertOne.Parameters);
                    SetParam("@proxy_type", 1, cmdInsertOne.Parameters);
                    cmdInsertOne.ExecuteNonQuery();
                    return true;
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.Message);
                    return false;
                }
            }
        }

        public bool UpdateDownloadEntry(FinishedDownloadItem entry)
        {
            lock (db)
            {
                try
                {
                    if (cmdUpdateOne == null)
                    {
                        cmdUpdateOne = new SQLiteCommand(@"UPDATE downloads SET name=@name, date_added=@date_added, size=@size, 
                                            download_type=@download_type, targetdir=@targetdir, primary_url=@primary_url,
                                            auth=@auth, user=@user, pass=@pass, proxy=@proxy, proxy_host=@proxy_host,
                                            proxy_port=@proxy_port, proxy_user=@proxy_user, proxy_pass=@proxy_pass, 
                                            proxy_type=@proxy_type WHERE id=@id", db);
                    }
                    SetParam("@id", entry.Id, cmdUpdateOne.Parameters);
                    SetParam("@name", entry.Name, cmdUpdateOne.Parameters);
                    SetParam("@date_added", entry.DateAdded.ToBinary(), cmdUpdateOne.Parameters);
                    SetParam("@size", entry.Size, cmdUpdateOne.Parameters);
                    SetParam("@download_type", entry.DownloadType, cmdUpdateOne.Parameters);
                    SetParam("@primary_url", entry.PrimaryUrl, cmdUpdateOne.Parameters);
                    SetParam("@auth", entry.Authentication.HasValue ? 1 : 0, cmdUpdateOne.Parameters);
                    SetParam("@user", entry.Authentication?.UserName ?? null, cmdUpdateOne.Parameters);
                    SetParam("@pass", entry.Authentication?.Password ?? null, cmdUpdateOne.Parameters);
                    SetParam("@proxy", (int)(entry.Proxy?.ProxyType ?? 0), cmdUpdateOne.Parameters);
                    SetParam("@proxy_host", entry.Proxy?.Host ?? null, cmdUpdateOne.Parameters);
                    SetParam("@proxy_port", (int)(entry.Proxy?.Port ?? 0), cmdUpdateOne.Parameters);
                    SetParam("@proxy_user", entry.Proxy?.UserName ?? null, cmdUpdateOne.Parameters);
                    SetParam("@proxy_pass", entry.Proxy?.Password ?? null, cmdUpdateOne.Parameters);
                    SetParam("@proxy_type", 1, cmdUpdateOne.Parameters);
                    SetParam("@targetdir", entry.TargetDir, cmdUpdateOne.Parameters);
                    cmdUpdateOne.ExecuteNonQuery();
                    return true;
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.Message);
                    return false;
                }
            }
        }

        public bool UpdateDownloadProgress(string id, int progress)
        {
            lock (db)
            {
                try
                {
                    if (cmdUpdateProgress == null)
                    {
                        cmdUpdateProgress = new SQLiteCommand("UPDATE downloads SET progress=@progress WHERE id=@id", db);
                    }
                    SetParam("@progress", progress, cmdUpdateProgress.Parameters);
                    SetParam("@id", id, cmdUpdateProgress.Parameters);
                    //cmdUpdateProgress.Parameters["@progress"].Value = progress;
                    //cmdUpdateProgress.Parameters["@id"].Value = id;
                    cmdUpdateProgress.ExecuteNonQuery();
                    return true;
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.Message);
                    return false;
                }
            }
        }

        public bool UpdateDownloadFolder(string id, string folder)
        {
            lock (db)
            {
                try
                {
                    if (cmdUpdateTargetDir == null)
                    {
                        cmdUpdateTargetDir = new SQLiteCommand("UPDATE downloads SET targetdir=@targetdir WHERE id=@id", db);
                    }
                    SetParam("@targetdir", folder, cmdUpdateTargetDir.Parameters);
                    SetParam("@id", id, cmdUpdateTargetDir.Parameters);
                    //cmdUpdateProgress.Parameters["@targetdir"].Value = folder;
                    //cmdUpdateProgress.Parameters["@id"].Value = id;
                    cmdUpdateProgress.ExecuteNonQuery();
                    return true;
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.Message);
                    return false;
                }
            }
        }

        private void SetParam<T>(string name, T value, SQLiteParameterCollection param)
        {
            if (!param.Contains(name))
            {
                param.AddWithValue(name, value);
                return;
            }
            param[name].Value = value;
        }

        public bool MarkAsFinished(string id, long finalFileSize, string file, string folder)
        {
            lock (db)
            {
                try
                {
                    if (cmdMarkFinished == null)
                    {
                        cmdMarkFinished = new SQLiteCommand("UPDATE downloads SET targetdir=@targetdir, name=@name, " +
                            "size=@finalFileSize, completed=@completed WHERE id=@id", db);
                    }
                    SetParam("@targetdir", folder, cmdMarkFinished.Parameters);
                    SetParam("@name", file, cmdMarkFinished.Parameters);
                    SetParam("@finalFileSize", finalFileSize, cmdMarkFinished.Parameters);
                    SetParam("@id", id, cmdMarkFinished.Parameters);
                    SetParam("@completed", 1, cmdMarkFinished.Parameters);
                    cmdMarkFinished.ExecuteNonQuery();
                    return true;
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.Message);
                    return false;
                }
            }
        }

        public bool UpdateDownloadStatus(string id, DownloadStatus status)
        {
            lock (db)
            {
                try
                {
                    if (cmdUpdateStatus == null)
                    {
                        cmdUpdateStatus = new SQLiteCommand("UPDATE downloads SET status=@status WHERE id=@id", db);
                    }
                    SetParam("@status", (int)status, cmdUpdateStatus.Parameters);
                    SetParam("@id", id, cmdUpdateStatus.Parameters);
                    cmdUpdateStatus.ExecuteNonQuery();
                    return true;
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.Message);
                    return false;
                }
            }
        }

        public bool UpdateNameAndSize(string id, long size, string name)
        {
            lock (db)
            {
                try
                {
                    if (cmdUpdateNameAndSize == null)
                    {
                        cmdUpdateNameAndSize = new SQLiteCommand("UPDATE downloads SET name=@name, size=@size WHERE id=@id", db);
                    }
                    SetParam("@id", id, cmdUpdateNameAndSize.Parameters);
                    SetParam("@name", name, cmdUpdateNameAndSize.Parameters);
                    SetParam("@size", size, cmdUpdateNameAndSize.Parameters);
                    cmdUpdateNameAndSize.ExecuteNonQuery();
                    return true;
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.Message);
                    return false;
                }
            }
        }

        public bool UpdateNameAndFolder(string id, string name, string folder)
        {
            lock (db)
            {
                try
                {
                    if (cmdUpdateNameAndFolder == null)
                    {
                        cmdUpdateNameAndFolder = new SQLiteCommand("UPDATE downloads SET name=@name, targetdir=@targetdir WHERE id=@id", db);
                    }
                    SetParam("@name", name, cmdUpdateNameAndFolder.Parameters);
                    SetParam("@targetdir", folder, cmdUpdateNameAndFolder.Parameters);
                    SetParam("@id", id, cmdUpdateNameAndFolder.Parameters);
                    cmdUpdateNameAndFolder.ExecuteNonQuery();
                    return true;
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.Message);
                    return false;
                }
            }
        }

        public bool RemoveAllFinished()
        {
            lock (db)
            {
                try
                {
                    using var cmdClearAllFinished = new SQLiteCommand("DELETE FROM downloads WHERE completed=1", db);
                    cmdClearAllFinished.ExecuteNonQuery();
                    return true;
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.Message);
                    return false;
                }
            }
        }

        public bool RemoveDownloadById(string id)
        {
            lock (db)
            {
                try
                {
                    if (cmdDelete == null)
                    {
                        cmdDelete = new SQLiteCommand("DELETE FROM downloads WHERE id=@id", db);
                    }
                    SetParam("@id", id, cmdDelete.Parameters);
                    cmdDelete.ExecuteNonQuery();
                    return true;
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.Message);
                    return false;
                }
            }
        }
    }

    public enum QueryMode
    {
        Finished,
        InProgress,
        All
    }
}
