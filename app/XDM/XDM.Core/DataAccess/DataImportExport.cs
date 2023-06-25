using System;
using System.IO;
using TraceLog;
#if NET5_0_OR_GREATER
using Microsoft.Data.Sqlite;
#else
using System.Data.SQLite;
#endif

namespace XDM.Core.DataAccess
{
    internal static class DataImportExport
    {
//        public static bool CopyToFile(
//#if NET5_0_OR_GREATER
//            SqliteConnection sql,
//#else
//            SQLiteConnection sql,
//#endif
//            string file)
//        {
//            try
//            {
//#if !NET5_0_OR_GREATER
//                if (!File.Exists(file))
//                {
//                    SQLiteConnection.CreateFile(file);
//                }
//#endif
//#if NET5_0_OR_GREATER
//                var cs = $"Data Source={file}";
//                using var dest = new SqliteConnection(cs);
//#else
//                var cs = $"URI=file:{file}";
//                using var dest = new SQLiteConnection(cs);
//#endif
//                dest.Open();
//#if NET5_0_OR_GREATER
//                sql.BackupDatabase(dest, "main", "main");
//#else
//                sql.BackupDatabase(dest, "main", "main", -1, null, 0);
//#endif
//                dest.Close();
//                dest.Dispose();
//                return true;
//            }
//            catch (Exception ex)
//            {
//                Log.Debug(ex, ex.Message);
//                return false;
//            }
//        }

        public static bool CopyFromFile(
#if NET5_0_OR_GREATER
            SqliteConnection sql,
#else
            SQLiteConnection sql,
#endif
            string file)
        {
            try
            {
                using var attachCmd = new
#if NET5_0_OR_GREATER
            SqliteCommand
#else
            SQLiteCommand
#endif
                    ($"ATTACH '{file}' as db", sql);
                attachCmd.ExecuteNonQuery();
                var tx = sql.BeginTransaction();
                try
                {
                    using var mergeCmd = new
#if NET5_0_OR_GREATER
            SqliteCommand
#else
            SQLiteCommand
#endif
                        ($"INSERT OR IGNORE INTO downloads SELECT * FROM db.downloads", sql);
                    mergeCmd.ExecuteNonQuery();
                    tx.Commit();
                }
                catch (Exception ex)
                {
                    tx.Rollback();
                    Log.Debug("Error during merge insert, performing rollback!!");
                    Log.Debug(ex, ex.Message);
                }
                using var detachCmd = new
#if NET5_0_OR_GREATER
            SqliteCommand
#else
            SQLiteCommand
#endif
                    ($"DETACH db", sql);
                detachCmd.ExecuteNonQuery();
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
