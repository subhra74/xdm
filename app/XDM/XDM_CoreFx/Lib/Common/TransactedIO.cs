using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using TraceLog;

namespace XDM.Core.Lib.Common
{
    public static class TransactedIO

    {
        public static IEnumerable<FinishedDownloadEntry> ReadFinishedList(string fileName, string folder)
        {
            try
            {
                var file = Path.Combine(folder, fileName);
                var bak = Path.Combine(folder, "~" + fileName);
                if (File.Exists(file))
                {
                    return ReadFinishedList(file);
                }
                if (File.Exists(bak))
                {
                    return ReadFinishedList(bak);
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "TransactedWriter.Read");
            }
            return new List<FinishedDownloadEntry>(0);
        }

        private static List<FinishedDownloadEntry> ReadFinishedList(string file)
        {
            var list = new List<FinishedDownloadEntry>();
            using var reader = new BinaryReader(new FileStream(file, FileMode.Open));
            try
            {
                var count = reader.ReadInt32();
                for (var i = 0; i < count; i++)
                {
                    var version = reader.ReadInt32();
                    var entry = FinishedDownloadEntry.Deserialize(version, reader);
                    list.Add(entry);
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
            return list;
        }

        public static List<InProgressDownloadEntry> ReadInProgressList(string fileName, string folder)
        {
            try
            {
                var file = Path.Combine(folder, fileName);
                var bak = Path.Combine(folder, "~" + fileName);
                if (File.Exists(file))
                {
                    return ReadInProgressList(file);
                }
                if (File.Exists(bak))
                {
                    return ReadInProgressList(bak);
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "TransactedWriter.Read");
            }
            return new List<InProgressDownloadEntry>(0);
        }

        private static List<InProgressDownloadEntry> ReadInProgressList(string file)
        {
            var list = new List<InProgressDownloadEntry>();
            using var reader = new BinaryReader(new FileStream(file, FileMode.Open));
            try
            {
                var count = reader.ReadInt32();
                for (var i = 0; i < count; i++)
                {
                    var version = reader.ReadInt32();
                    var entry = InProgressDownloadEntry.Deserialize(version, reader);
                    list.Add(entry);
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
            return list;
        }

        private static void WriteInProgressList(IEnumerable<InProgressDownloadEntry> list, string file)
        {
            var count = list.Count();
            using var writer = new BinaryWriter(new FileStream(file, FileMode.Create));
            writer.Write(count);
            foreach (var item in list)
            {
                item.Serialize(writer);
            }
        }

        public static bool WriteInProgressList(IEnumerable<InProgressDownloadEntry> list, string fileName, string folder)
        {
            try
            {
                var bak1 = Path.Combine(folder, fileName + ".bak");
                var bak2 = Path.Combine(folder, "~" + fileName);
                var file = Path.Combine(folder, fileName);
                WriteInProgressList(list, bak1);

                if (File.Exists(file))
                {
                    if (File.Exists(bak2))
                    {
                        File.Delete(bak2);
                    }
                    File.Move(file, bak2);
                }
                File.Move(bak1, file);
                return true;
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "TransactedWriter.Write");
            }
            return false;
        }

        private static void WriteFinishedList(IEnumerable<FinishedDownloadEntry> list, string file)
        {
            var count = list.Count();
            using var writer = new BinaryWriter(new FileStream(file, FileMode.Create));
            writer.Write(count);
            foreach (var item in list)
            {
                item.Serialize(writer);
            }
        }

        public static bool WriteFinishedList(IEnumerable<FinishedDownloadEntry> list, string fileName, string folder)
        {
            try
            {
                var bak1 = Path.Combine(folder, fileName + ".bak");
                var bak2 = Path.Combine(folder, "~" + fileName);
                var file = Path.Combine(folder, fileName);
                WriteFinishedList(list, bak1);

                if (File.Exists(file))
                {
                    if (File.Exists(bak2))
                    {
                        File.Delete(bak2);
                    }
                    File.Move(file, bak2);
                }
                File.Move(bak1, file);
                return true;
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "TransactedWriter.Write");
            }
            return false;
        }

        public static bool Write(string text, string fileName, string folder)
        {
            try
            {
                var bak1 = Path.Combine(folder, fileName + ".bak");
                var bak2 = Path.Combine(folder, "~" + fileName);
                var file = Path.Combine(folder, fileName);
                File.WriteAllText(bak1, text);

                if (File.Exists(file))
                {
                    if (File.Exists(bak2))
                    {
                        File.Delete(bak2);
                    }
                    File.Move(file, bak2);
                }
                File.Move(bak1, file);
                return true;
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "TransactedWriter.Write");
            }
            return false;
        }

        public static string? Read(string fileName, string folder)
        {
            try
            {
                var file = Path.Combine(folder, fileName);
                var bak = Path.Combine(folder, "~" + fileName);
                if (File.Exists(file))
                {
                    return File.ReadAllText(file);
                }
                if (File.Exists(bak))
                {
                    return File.ReadAllText(bak);
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "TransactedWriter.Read");
            }
            return null;
        }

        public static bool WriteBytes(byte[] bytes, string fileName, string folder)
        {
            try
            {
                var bak1 = Path.Combine(folder, fileName + ".bak");
                var bak2 = Path.Combine(folder, "~" + fileName);
                var file = Path.Combine(folder, fileName);
                File.WriteAllBytes(bak1, bytes);

                if (File.Exists(file))
                {
                    if (File.Exists(bak2))
                    {
                        File.Delete(bak2);
                    }
                    File.Move(file, bak2);
                }
                File.Move(bak1, file);
                return true;
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "TransactedWriter.Write");
            }
            return false;
        }

        public static byte[]? ReadBytes(string fileName, string folder)
        {
            try
            {
                var file = Path.Combine(folder, fileName);
                var bak = Path.Combine(folder, "~" + fileName);
                if (File.Exists(file))
                {
                    return File.ReadAllBytes(file);
                }
                if (File.Exists(bak))
                {
                    return File.ReadAllBytes(bak);
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "TransactedWriter.Read");
            }
            return null;
        }
    }
}
