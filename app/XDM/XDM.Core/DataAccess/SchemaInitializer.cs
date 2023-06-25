using System;

namespace XDM.Core.DataAccess
{
    internal static class SchemaInitializer
    {
        private static void CreateTablesIfNotExists(SqliteWrapper c)
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
            using var cmd = c.CreateCommand(query);
            cmd.ExecuteNonQuery();
        }

        public static void Init(SqliteWrapper c)
        {
            CreateTablesIfNotExists(c);
        }
    }
}
