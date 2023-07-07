using System;
using System.Collections.Generic;
using System.Text;
using System.IO;
using TraceLog;
#if OSX
using Microsoft.Data.Sqlite;
#else
using System.Data.SQLite;
#endif

namespace XDM.Core.DataAccess
{
    internal interface ISQLiteCommandWrapper : IDisposable
    {
        void SetParam<T>(string name, T value);
        void ExecuteNonQuery();
        ISQLiteDataReaderWrapper ExecuteReader();
    }

    internal interface ISQLiteDataReaderWrapper : IDisposable
    {
        string? GetSafeString(int col);
        Int32 GetInt32(int col);
        Int64 GetInt64(int col);
        bool Read();
    }

#if OSX
    internal class SQLiteDataReaderWrapper : ISQLiteDataReaderWrapper
    {
        private SqliteDataReader _reader;

        internal SQLiteDataReaderWrapper(SqliteDataReader reader)
        {
            _reader = reader;
        }

        public string? GetSafeString(int col)
        {
            if (!_reader.IsDBNull(col))
            {
                return _reader.GetString(col);
            }
            return null;
        }

        public Int32 GetInt32(int col)
        {
            return _reader.GetInt32(col);
        }

        public Int64 GetInt64(int col)
        {
            return _reader.GetInt64(col);
        }

        public bool Read()
        {
            return _reader.Read();
        }

        public void Dispose()
        {
            this._reader.Dispose();
        }
    }

    internal class SQLiteCommandWrapper : ISQLiteCommandWrapper
    {
        private SqliteCommand _command;

        public SQLiteCommandWrapper(SqliteCommand command)
        {
            this._command = command;
        }

        public void SetParam<T>(string name, T value)
        {
            if (!this._command.Parameters.Contains(name))
            {
                this._command.Parameters.AddWithValue(name, value == null ? DBNull.Value : value);
                return;
            }
            this._command.Parameters[name].Value = value == null ? DBNull.Value : value;
        }

        public void ExecuteNonQuery()
        {
            this._command.ExecuteNonQuery();
        }

        public ISQLiteDataReaderWrapper ExecuteReader()
        {
            return new SQLiteDataReaderWrapper(this._command.ExecuteReader());
        }

        public void Dispose()
        {
            this._command.Dispose();
        }
    }
#else
    internal class SQLiteDataReaderWrapper : ISQLiteDataReaderWrapper
    {
        private SQLiteDataReader _reader;

        internal SQLiteDataReaderWrapper(SQLiteDataReader reader)
        {
            _reader = reader;
        }

        public string? GetSafeString(int col)
        {
            if (!_reader.IsDBNull(col))
            {
                return _reader.GetString(col);
            }
            return null;
        }

        public Int32 GetInt32(int col)
        {
            return _reader.GetInt32(col);
        }

        public Int64 GetInt64(int col)
        {
            return _reader.GetInt64(col);
        }

        public bool Read()
        {
            return _reader.Read();
        }

        public void Dispose()
        {
            _reader.Dispose();
        }
    }

    internal class SQLiteCommandWrapper : ISQLiteCommandWrapper
    {
        private SQLiteCommand _command;

        public SQLiteCommandWrapper(SQLiteCommand command)
        {
            this._command = command;
        }

        public void SetParam<T>(string name, T value)
        {
            if (!this._command.Parameters.Contains(name))
            {
                this._command.Parameters.AddWithValue(name, value);
                return;
            }
            this._command.Parameters[name].Value = value;
        }

        public void ExecuteNonQuery()
        {
            this._command.ExecuteNonQuery();
        }

        public ISQLiteDataReaderWrapper ExecuteReader()
        {
            return new SQLiteDataReaderWrapper(this._command.ExecuteReader());
        }

        public void Dispose()
        {
            this._command.Dispose();
        }
    }
#endif

    internal class SqliteWrapper
    {
#if OSX
        private SqliteConnection _connection;
        public SqliteConnection Connection => _connection;
#else
        private SQLiteConnection _connection;
        public SQLiteConnection Connection => _connection;
#endif


        public SqliteWrapper(string file)
        {
            Log.Debug($"Initialize SQLite from '{file}'...");
#if OSX
            string cs = $"Data Source={file}";
            _connection = new SqliteConnection(cs);
            _connection.Open();
#else
            string cs = $"URI=file:{file}";
            if (!File.Exists(file))
            {
                SQLiteConnection.CreateFile(file);
            }
            _connection = new SQLiteConnection(cs);
            _connection.Open();
#endif
            Log.Debug("Initialize SQLite DONE.");
        }

        public ISQLiteCommandWrapper CreateCommand(string sql)
        {
#if OSX
            return new SQLiteCommandWrapper(new SqliteCommand(sql, _connection));
#else
            return new SQLiteCommandWrapper(new SQLiteCommand(sql, _connection));
#endif
        }
    }
}
