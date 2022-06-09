using System;
using System.Collections.Generic;
using System.Data.SQLite;
using System.Linq;
using System.Text;

namespace XDM.Core.Lib.DataAccess
{
    public static class SchemaInitializer
    {
        private static void CreateTablesIfNotExists(SQLiteConnection c)
        {
            var query = @"CREATE TABLE IF NOT EXISTS downloads(
                                            id TEXT PRIMARY KEY,
                                            completed INT,
                                            name TEXT,
                                            date_added INT,
                                            size INT,
                                            status INT,
                                            progress INT,
                                            download_type TEXT,
                                            filenamefetchmode INT,
                                            maxspeedlimitinkib INT,
                                            targetdir TEXT,
                                            primary_url TEXT,
                                            referer_url TEXT,
                                            auth INT,
                                            user TEXT,
                                            pass TEXT,
                                            proxy INT,
                                            proxy_host TEXT,
                                            proxy_port INT,
                                            proxy_user TEXT,
                                            proxy_pass TEXT,
                                            proxy_type INT
                                        ) WITHOUT ROWID";
            using var cmd = new SQLiteCommand(c);
            cmd.CommandText = query;
            cmd.ExecuteNonQuery();
        }

        public static void Init(SQLiteConnection c)
        {
            CreateTablesIfNotExists(c);
        }
    }
}
