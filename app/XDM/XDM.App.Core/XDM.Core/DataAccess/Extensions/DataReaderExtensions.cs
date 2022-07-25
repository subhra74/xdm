using System;
using System.Collections.Generic;
using System.Data.SQLite;
using System.Linq;
using System.Text;

namespace XDM.Core.DataAccess.Extensions
{
    public static class DataReaderExtensions
    {
        public static string GetSafeString(this SQLiteDataReader r, int index)
        {
            if (!r.IsDBNull(index))
            {
                return r.GetString(index);
            }
#pragma warning disable CS8603 // Possible null reference return.
            return null;
#pragma warning restore CS8603 // Possible null reference return.
        }
    }
}
