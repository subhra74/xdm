using System;
using System.Collections.Generic;
using TraceLog;
using XDM.Core.Downloader;

namespace XDM.Core.DataAccess
{
    public class DownloadDatabase
    {
        private SqliteWrapper db;

        internal DownloadDatabase(SqliteWrapper db)
        {
            this.db = db;
        }

        private ISQLiteCommandWrapper cmdFetchAll, cmdFetchConditional, cmdFetchOne,
            cmdUpdateProgress, cmdUpdateTargetDir,
            cmdInsertOne, cmdMarkFinished, cmdUpdateStatus,
            cmdUpdateNameAndSize, cmdUpdateNameAndFolder, cmdUpdateOne,
            cmdDelete;

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
                    ISQLiteCommandWrapper sqlCommand;
                    if (queryMode == QueryMode.All)
                    {
                        if (cmdFetchAll == null)
                        {
                            cmdFetchAll = db.CreateCommand("SELECT * FROM downloads");
                        }
                        sqlCommand = cmdFetchAll;
                    }
                    else
                    {
                        if (cmdFetchConditional == null)
                        {
                            cmdFetchConditional = db.CreateCommand("SELECT * FROM downloads WHERE completed=@completed");
                        }
                        cmdFetchConditional.SetParam("@completed", queryMode == QueryMode.InProgress ? 0 : 1);
                        sqlCommand = cmdFetchConditional;
                    }
                    using var r = sqlCommand.ExecuteReader();
                    while (r.Read())
                    {
                        var id = r.GetSafeString(0)!;
                        var inProgress = r.GetInt32(1) == 0;
                        DownloadItemBase entry = r.GetInt32(1) == 0 ? new InProgressDownloadItem() : new FinishedDownloadItem();
                        entry.Id = id;
                        entry.Name = r.GetSafeString(2)!;
                        entry.DateAdded = DateTime.FromBinary(r.GetInt64(3));
                        entry.Size = r.GetInt64(4);
                        entry.DownloadType = r.GetSafeString(7)!;
                        entry.FileNameFetchMode = (FileNameFetchMode)r.GetInt32(8);
                        entry.MaxSpeedLimitInKiB = r.GetInt32(9);
                        entry.TargetDir = r.GetSafeString(10);
                        entry.PrimaryUrl = r.GetSafeString(11)!;
                        entry.RefererUrl = r.GetSafeString(12)!;
                        if (r.GetInt32(13) == 1)
                        {
                            var user = r.GetSafeString(14);
                            var pass = r.GetSafeString(15);
                            if (user != null)
                            {
                                entry.Authentication = new AuthenticationInfo
                                {
                                    UserName = user,
                                    Password = pass!
                                };
                            }
                        }
                        var proxy = new ProxyInfo { };
                        proxy.ProxyType = (ProxyType)r.GetInt32(16);
                        proxy.Host = r.GetSafeString(17)!;
                        proxy.Port = r.GetInt32(18);
                        proxy.UserName = r.GetSafeString(19)!;
                        proxy.Password = r.GetSafeString(20)!;
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
                        cmdFetchOne = db.CreateCommand("SELECT * FROM downloads WHERE id=@id");
                    }
                    cmdFetchOne.SetParam("@id", id);
                    //cmdFetchOne.Parameters["@id"].Value = id;
                    using var r = cmdFetchOne.ExecuteReader();
                    if (r.Read())
                    {
                        var inProgress = r.GetInt32(1) == 0;
                        DownloadItemBase entry = r.GetInt32(1) == 0 ? new InProgressDownloadItem() : new FinishedDownloadItem();
                        entry.Id = id;
                        entry.Name = r.GetSafeString(2)!;
                        entry.DateAdded = DateTime.FromBinary(r.GetInt64(3));
                        entry.Size = r.GetInt64(4);
                        entry.DownloadType = r.GetSafeString(7)!;
                        entry.FileNameFetchMode = (FileNameFetchMode)r.GetInt32(8);
                        entry.MaxSpeedLimitInKiB = r.GetInt32(9);
                        entry.TargetDir = r.GetSafeString(10);
                        entry.PrimaryUrl = r.GetSafeString(11)!;
                        entry.RefererUrl = r.GetSafeString(12)!;
                        if (r.GetInt32(13) == 1)
                        {
                            var user = r.GetSafeString(14);
                            var pass = r.GetSafeString(15);
                            if (user != null)
                            {
                                entry.Authentication = new AuthenticationInfo
                                {
                                    UserName = user,
                                    Password = pass!
                                };
                            }
                        }
                        var proxy = new ProxyInfo { };
                        proxy.ProxyType = (ProxyType)r.GetInt32(16);
                        proxy.Host = r.GetSafeString(17)!;
                        proxy.Port = r.GetInt32(18);
                        proxy.UserName = r.GetSafeString(19)!;
                        proxy.Password = r.GetSafeString(20)!;
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
                        cmdInsertOne = db.CreateCommand(@"INSERT INTO downloads(
                                            id, completed, name, date_added, size, status, 
                                            progress, download_type, filenamefetchmode, maxspeedlimitinkib, targetdir, primary_url,
                                            referer_url, auth, user, pass, proxy, proxy_host,
                                            proxy_port, proxy_user, proxy_pass, proxy_type)
                                            VALUES(
                                            @id, @completed, @name, @date_added, @size, @status, 
                                            @progress, @download_type, @filenamefetchmode, @maxspeedlimitinkib, @targetdir, @primary_url,
                                            @referer_url, @auth, @user, @pass, @proxy, @proxy_host, 
                                            @proxy_port, @proxy_user, @proxy_pass, @proxy_type)");
                    }
                    cmdInsertOne.SetParam("@id", entry.Id);
                    cmdInsertOne.SetParam("@completed", 0);
                    cmdInsertOne.SetParam("@name", entry.Name);
                    cmdInsertOne.SetParam("@date_added", entry.DateAdded.ToBinary());
                    cmdInsertOne.SetParam("@size", entry.Size);
                    cmdInsertOne.SetParam("@status", (int)entry.Status);
                    cmdInsertOne.SetParam("@progress", entry.Progress);
                    cmdInsertOne.SetParam("@download_type", entry.DownloadType);
                    cmdInsertOne.SetParam("@filenamefetchmode", (int)entry.FileNameFetchMode);
                    cmdInsertOne.SetParam("@maxspeedlimitinkib", entry.MaxSpeedLimitInKiB);
                    cmdInsertOne.SetParam("@targetdir", entry.TargetDir);
                    cmdInsertOne.SetParam("@primary_url", entry.PrimaryUrl);
                    cmdInsertOne.SetParam("@referer_url", entry.RefererUrl);
                    cmdInsertOne.SetParam("@auth", entry.Authentication.HasValue ? 1 : 0);
                    cmdInsertOne.SetParam("@user", entry.Authentication?.UserName ?? null);
                    cmdInsertOne.SetParam("@pass", entry.Authentication?.Password ?? null);
                    cmdInsertOne.SetParam("@proxy", (int)(entry.Proxy?.ProxyType ?? 0));
                    cmdInsertOne.SetParam("@proxy_host", entry.Proxy?.Host ?? null);
                    cmdInsertOne.SetParam("@proxy_port", (int)(entry.Proxy?.Port ?? 0));
                    cmdInsertOne.SetParam("@proxy_user", entry.Proxy?.UserName ?? null);
                    cmdInsertOne.SetParam("@proxy_pass", entry.Proxy?.Password ?? null);
                    cmdInsertOne.SetParam("@proxy_type", 1);
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
                        cmdUpdateOne = db.CreateCommand(@"UPDATE downloads SET name=@name, date_added=@date_added, size=@size, 
                                            download_type=@download_type, targetdir=@targetdir, primary_url=@primary_url,
                                            auth=@auth, user=@user, pass=@pass, proxy=@proxy, proxy_host=@proxy_host,
                                            proxy_port=@proxy_port, proxy_user=@proxy_user, proxy_pass=@proxy_pass, 
                                            proxy_type=@proxy_type WHERE id=@id");
                    }
                    cmdUpdateOne.SetParam("@id", entry.Id);
                    cmdUpdateOne.SetParam("@name", entry.Name);
                    cmdUpdateOne.SetParam("@date_added", entry.DateAdded.ToBinary());
                    cmdUpdateOne.SetParam("@size", entry.Size);
                    cmdUpdateOne.SetParam("@download_type", entry.DownloadType);
                    cmdUpdateOne.SetParam("@primary_url", entry.PrimaryUrl);
                    cmdUpdateOne.SetParam("@auth", entry.Authentication.HasValue ? 1 : 0);
                    cmdUpdateOne.SetParam("@user", entry.Authentication?.UserName ?? null);
                    cmdUpdateOne.SetParam("@pass", entry.Authentication?.Password ?? null);
                    cmdUpdateOne.SetParam("@proxy", (int)(entry.Proxy?.ProxyType ?? 0));
                    cmdUpdateOne.SetParam("@proxy_host", entry.Proxy?.Host ?? null);
                    cmdUpdateOne.SetParam("@proxy_port", (int)(entry.Proxy?.Port ?? 0));
                    cmdUpdateOne.SetParam("@proxy_user", entry.Proxy?.UserName ?? null);
                    cmdUpdateOne.SetParam("@proxy_pass", entry.Proxy?.Password ?? null);
                    cmdUpdateOne.SetParam("@proxy_type", 1);
                    cmdUpdateOne.SetParam("@targetdir", entry.TargetDir);
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
                        cmdUpdateProgress = db.CreateCommand("UPDATE downloads SET progress=@progress WHERE id=@id");
                    }
                    cmdUpdateProgress.SetParam("@progress", progress);
                    cmdUpdateProgress.SetParam("@id", id);
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
                        cmdUpdateTargetDir = db.CreateCommand("UPDATE downloads SET targetdir=@targetdir WHERE id=@id");
                    }
                    cmdUpdateTargetDir.SetParam("@targetdir", folder);
                    cmdUpdateTargetDir.SetParam("@id", id);
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

        public bool MarkAsFinished(string id, long finalFileSize, string file, string folder)
        {
            lock (db)
            {
                try
                {
                    if (cmdMarkFinished == null)
                    {
                        cmdMarkFinished = db.CreateCommand("UPDATE downloads SET targetdir=@targetdir, name=@name, " +
                            "size=@finalFileSize, completed=@completed WHERE id=@id");
                    }
                    cmdMarkFinished.SetParam("@targetdir", folder);
                    cmdMarkFinished.SetParam("@name", file);
                    cmdMarkFinished.SetParam("@finalFileSize", finalFileSize);
                    cmdMarkFinished.SetParam("@id", id);
                    cmdMarkFinished.SetParam("@completed", 1);
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
                        cmdUpdateStatus = db.CreateCommand("UPDATE downloads SET status=@status WHERE id=@id");
                    }
                    cmdUpdateStatus.SetParam("@status", (int)status);
                    cmdUpdateStatus.SetParam("@id", id);
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
                        cmdUpdateNameAndSize = db.CreateCommand("UPDATE downloads SET name=@name, size=@size WHERE id=@id");
                    }
                    cmdUpdateNameAndSize.SetParam("@id", id);
                    cmdUpdateNameAndSize.SetParam("@name", name);
                    cmdUpdateNameAndSize.SetParam("@size", size);
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
                        cmdUpdateNameAndFolder = db.CreateCommand("UPDATE downloads SET name=@name, targetdir=@targetdir WHERE id=@id");
                    }
                    cmdUpdateNameAndFolder.SetParam("@name", name);
                    cmdUpdateNameAndFolder.SetParam("@targetdir", folder);
                    cmdUpdateNameAndFolder.SetParam("@id", id);
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
                    using var cmdClearAllFinished = db.CreateCommand("DELETE FROM downloads WHERE completed=1");
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
                        cmdDelete = db.CreateCommand("DELETE FROM downloads WHERE id=@id");
                    }
                    cmdDelete.SetParam("@id", id);
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
