using System;
using System.Collections.Generic;
using System.Text;
#if NET5_0_OR_GREATER
using Microsoft.Data.Sqlite;
#else
using System.Data.SQLite;
#endif

namespace XDM.Core.DataAccess
{
    internal interface ISQLiteConnection
    {

    }

    internal interface ISQLiteCommand
    {
        void SetParam<T>(string name, T value);
        void ExecuteNonQuery();
    }

    internal interface ISQLiteDataReader
    {

    }

    internal class SqliteDataReader
    {
    }
}
