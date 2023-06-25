using System;
using TraceLog;

namespace XDM.Core.DataAccess
{
    public class AppDB
    {
        private static object lockObj = new();
        private bool init = false;
        private SqliteWrapper db;
        private AppDB() { }
        private DownloadDatabase downloadsDB;
        public DownloadDatabase Downloads => downloadsDB;
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
                    db = new SqliteWrapper(file);
                    SchemaInitializer.Init(db);
                    this.downloadsDB = new DownloadDatabase(db);
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

        //public bool Export(string file)
        //{
        //    try
        //    {
        //        DataImportExport.CopyToFile(db.Connection, file);
        //        return true;
        //    }
        //    catch (Exception e)
        //    {
        //        Log.Debug(e, e.Message);
        //        return false;
        //        throw;
        //    }
        //}

        public bool Import(string file)
        {
            try
            {
                DataImportExport.CopyFromFile(db.Connection, file);
                return true;
            }
            catch (Exception e)
            {
                Log.Debug(e, e.Message);
                return false;
                throw;
            }
        }
    }
}
