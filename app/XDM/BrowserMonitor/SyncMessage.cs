using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using XDM.Core.Lib.Util;

namespace BrowserMonitoring
{
    public class SyncMessage
    {
        public bool Enabled { get; set; }
        public string[] BlockedHosts { get; set; } = new string[0];
        public string[] VideoUrls { get; set; } = new string[0];
        public string[] FileExts { get; set; } = new string[0];
        public string[] VidExts { get; set; } = new string[0];
        public List<VideoItem> VidList { get; set; } = new List<VideoItem>(0);
        public string[] MimeList { get; set; } = new string[0];
        public string[] BlockedMimeList { get; set; } = new string[0];
        public string[] VideoUrlsWithPostReq { get; set; } = new string[0];

        public byte[] Serialize()
        {
            using var ms = new MemoryStream();
            using var w = new BinaryWriter(ms);
            w.Write(this.Enabled);
            WriteStringArray(w, BlockedHosts);
            WriteStringArray(w, VideoUrls);
            WriteStringArray(w, FileExts);
            WriteStringArray(w, VidExts);
            WriteStringArray(w, MimeList);
            WriteStringArray(w, BlockedMimeList);
            WriteStringArray(w, VideoUrlsWithPostReq);
            w.Write(VidList.Count);
            foreach (var item in VidList)
            {
                w.Write(item.Id);
                w.Write(item.Info);
                w.Write(item.Text);
            }
            w.Close();
            ms.Close();
            return ms.ToArray();
        }

        public static SyncMessage Deserialize(byte[] bytes)
        {
            using var ms = new MemoryStream(bytes);
            using var r = new BinaryReader(ms);
            var msg = new SyncMessage();
            msg.Enabled = r.ReadBoolean();
            msg.BlockedHosts = ReadStringArray(r);
            msg.VideoUrls = ReadStringArray(r);
            msg.FileExts = ReadStringArray(r);
            msg.VidExts = ReadStringArray(r);
            msg.MimeList = ReadStringArray(r);
            msg.BlockedMimeList = ReadStringArray(r);
            msg.VideoUrlsWithPostReq = ReadStringArray(r);
            var c = r.ReadInt32();
            msg.VidList = new(c);
            for (int i = 0; i < c; i++)
            {
                msg.VidList.Add(new VideoItem
                {
                    Id = Helpers.ReadString(r),
                    Info = Helpers.ReadString(r),
                    Text = Helpers.ReadString(r)
                });
            }
            return msg;
        }

        private static void WriteStringArray(BinaryWriter w, string[] arr)
        {
            var c = arr?.Length ?? 0;
            w.Write(c);
            if (arr != null && arr.Length > 0)
            {
                foreach (var item in arr)
                {
                    w.Write(item ?? string.Empty);
                }
            }
        }

        private static string[] ReadStringArray(BinaryReader r)
        {
            var c = r.ReadInt32();
            var arr = new string[c];
            for (int i = 0; i < c; i++)
            {
                arr[i] = r.ReadString();
            }
            return arr;
        }
    }

    public struct VideoItem
    {
        public string Id { get; set; }
        public string Text { get; set; }
        public string Info { get; set; }
    }
}
