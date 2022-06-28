using System;
using System.Collections.Generic;
using System.Data.Common;
using System.Data.SQLite;
using System.IO;
using System.Linq;
using System.Text;
using TraceLog;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Downloader;

namespace XDM.Core.Lib.DataAccess
{
    public class AppDB
    {
        private static object lockObj = new();
        private bool init = false;
        private SQLiteConnection db;
        private AppDB() { }
        private DownloadsDB downloadsDB;
        public DownloadsDB DownloadsDB => downloadsDB;
        private static AppDB instance;
        public static AppDB Instance
        {
            get
            {
                lock (lockObj)
                {
                    if (instance == null)
                    {
                        instance = new AppDB();
                    }
                }
                return instance;
            }
        }

        public bool Init(string file)
        {
            lock (this)
            {
                try
                {
                    string cs = $"URI=file:{file}";
                    if (!File.Exists(file))
                    {
                        SQLiteConnection.CreateFile(file);
                    }
                    db = new SQLiteConnection(cs);
                    db.Open();
                    SchemaInitializer.Init(db);
                    this.downloadsDB = new DownloadsDB(db);
                    init = true;
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
}
