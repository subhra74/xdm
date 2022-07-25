using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using TraceLog;
using XDM.Core.Util;

namespace XDM.Core
{
    public static class TransactedIO
    {
        private static readonly byte[] marker = new[] { (byte)'E', (byte)'N', (byte)'D', (byte)'.' };
        public static List<FinishedDownloadEntry> ReadFinishedList(string fileName, string folder)
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
            var list = new List<FinishedDownloadEntry>(0);
            using var reader = new BinaryReader(new FileStream(file, FileMode.Open));
            try
            {
                list = SerializationHelper.DeserializeFinishedDownloadEntry(reader);
                //var count = reader.ReadInt32();
                //for (var i = 0; i < count; i++)
                //{
                //    var version = reader.ReadInt32();
                //    var entry = FinishedDownloadEntry.Deserialize(version, reader);
                //    list.Add(entry);
                //}
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
                list = SerializationHelper.DeserializeInProgressDownloadEntry(reader);
                //var count = reader.ReadInt32();
                //for (var i = 0; i < count; i++)
                //{
                //    var version = reader.ReadInt32();
                //    var entry = InProgressDownloadEntry.Deserialize(version, reader);
                //    list.Add(entry);
                //}
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
            return list;
        }

        private static void WriteInProgressList(IEnumerable<InProgressDownloadEntry> list, string file)
        {
            using var writer = new BinaryWriter(new FileStream(file, FileMode.Create));
            SerializationHelper.SerializeInProgressDownloadEntry(writer, new List<InProgressDownloadEntry>(list));
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
            using var writer = new BinaryWriter(new FileStream(file, FileMode.Create));
            SerializationHelper.SerializeFinishedDownloadEntry(writer, new List<FinishedDownloadEntry>(list));
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

        public delegate void StreamCallback(Stream stream);

        public static bool ReadStream(string fileName, string folder, StreamCallback callback)
        {
            var name1 = Path.Combine(folder, $"{fileName}.1");
            var name2 = Path.Combine(folder, $"{fileName}.2");
            //var name3 = Path.Combine(folder, $"{fileName}.3");

            if (File.Exists(name1) && ReadStream(name1, callback))
            {
                return true;
            }
            if (File.Exists(name2) && ReadStream(name2, callback))
            {
                return true;
            }
            //if (File.Exists(name3) && ReadStream(name3, callback))
            //{
            //    return true;
            //}
            return false;
        }

        private static bool ReadStream(string file, StreamCallback callback)
        {
            try
            {
                using var fs = new FileStream(file, FileMode.Open, FileAccess.Read);
                var b4 = new byte[4];
                if (fs.Read(b4, 0, 4) != 4)
                {
                    return false;
                }
                var pos = BitConverter.ToInt32(b4, 0);
                if (pos > fs.Length || pos < 0)
                {
                    return false;
                }
                fs.Seek(pos, SeekOrigin.Begin);
                if (fs.Read(b4, 0, 4) != 4)
                {
                    return false;
                }
                if (b4[0] == marker[0] && b4[1] == marker[1] && b4[2] == marker[2] && b4[3] == marker[3])
                {
                    fs.Seek(4, SeekOrigin.Begin);
                    callback(fs);
                    return true;
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "TransactedWriter.Write");
            }
            return false;
        }

        private static void WriteStream(string file, StreamCallback callback)
        {
            using var fs = new FileStream(file, FileMode.OpenOrCreate, FileAccess.ReadWrite);
            fs.Seek(4, SeekOrigin.Begin);
            callback(fs);
            var pos = fs.Position;
            fs.Write(marker, 0, 4);
            fs.Seek(0, SeekOrigin.Begin);
            fs.Write(BitConverter.GetBytes((int)pos), 0, 4);
            fs.Close();
        }

        public static bool WriteStream(string fileName, string folder, StreamCallback callback)
        {
            try
            {
                var name1 = Path.Combine(folder, $"{fileName}.1");
                var name2 = Path.Combine(folder, $"{fileName}.2");
                var name3 = Path.Combine(folder, $"{fileName}.3.{Guid.NewGuid()}");

                if (!File.Exists(name1))
                {
                    WriteStream(name1, callback);
                    return true;
                }
                else
                {
                    WriteStream(name2, callback);
                    File.Move(name1, name3);
                    File.Move(name2, name1);
                    File.Move(name3, name2);
                }
                return false;
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
