using System;
using System.Collections.Generic;
using System.IO;
using XDM.Messaging;

namespace BrowserMonitoring
{
    public struct RawBrowserMessageEnvelop
    {
        public string MessageType { get; set; }

        public RawBrowserMessage Message { get; set; }

        public RawBrowserMessage[] Messages { get; set; }

        public string[] VideoIds { get; set; }

        public string CustomData { get; set; }

        public void Serialize(BinaryWriter w)
        {
            RawBrowserMessageEnvelopSerializerV1.Serialize(this, w);
        }

        public static RawBrowserMessageEnvelop Deserialize(BinaryReader r)
        {
            var version = r.ReadInt32();
            if (version == 1)
            {
                return RawBrowserMessageEnvelopSerializerV1.Deserialize(r);
            }
            if (version == Int32.MaxValue) //custom data
            {
                var data = StreamHelper.ReadString(r);
                return new RawBrowserMessageEnvelop { MessageType = "custom", CustomData = data };
            }
            throw new InvalidDataException($"Version ${version} not supported.");
        }
    }

    internal static class RawBrowserMessageEnvelopSerializerV1
    {
        public static void Serialize(RawBrowserMessageEnvelop e, BinaryWriter w)
        {
            w.Write(1);
            w.Write(e.MessageType);
            w.Write(e.Message != null);
            if (e.Message != null)
            {
                w.Write(e.Message.Url ?? string.Empty);
                w.Write(e.Message.File ?? string.Empty);
                w.Write(e.Message.Method ?? string.Empty);
                w.Write(e.Message.RequestBody ?? string.Empty);
                StreamHelper.WriteStateHeaders(e.Message.RequestHeaders, w);
                StreamHelper.WriteStateHeaders(e.Message.ResponseHeaders, w);
                StreamHelper.WriteStateCookies(e.Message.Cookies, w);
            }
            var count = e.VideoIds?.Length ?? 0;
            w.Write(count);
            if (e.VideoIds != null && e.VideoIds.Length > 0)
            {
                foreach (var item in e.VideoIds)
                {
                    w.Write(item);
                }
            }
            w.Write(e.Messages != null);
            if (e.Messages != null)
            {
                count = e.Messages.Length;
                w.Write(count);
                foreach (var message in e.Messages)
                {
                    w.Write(message.Url ?? string.Empty);
                    w.Write(message.File ?? string.Empty);
                    w.Write(message.Method ?? string.Empty);
                    w.Write(message.RequestBody ?? string.Empty);
                    StreamHelper.WriteStateHeaders(message.RequestHeaders, w);
                    StreamHelper.WriteStateHeaders(message.ResponseHeaders, w);
                    StreamHelper.WriteStateCookies(message.Cookies, w);
                }
            }
        }

        public static RawBrowserMessageEnvelop Deserialize(BinaryReader r)
        {
            var e = new RawBrowserMessageEnvelop { };
            e.MessageType = StreamHelper.ReadString(r);
            if (r.ReadBoolean())
            {
                e.Message = new();
                e.Message.Url = StreamHelper.ReadString(r);
                e.Message.File = StreamHelper.ReadString(r);
                e.Message.Method = StreamHelper.ReadString(r);
                e.Message.RequestBody = StreamHelper.ReadString(r);
                StreamHelper.ReadStateHeaders(r, out Dictionary<string, List<string>> dict1);
                StreamHelper.ReadStateHeaders(r, out Dictionary<string, List<string>> dict2);
                StreamHelper.ReadStateCookies(r, out Dictionary<string, string> dict3);
                e.Message.RequestHeaders = dict1;
                e.Message.ResponseHeaders = dict2;
                e.Message.Cookies = dict3;
            }
            var count = r.ReadInt32();
            e.VideoIds = new string[count];
            for (int i = 0; i < count; i++)
            {
                e.VideoIds[i] = r.ReadString();
            }
            if (r.ReadBoolean())
            {
                count = r.ReadInt32();
                var list = new RawBrowserMessage[count];
                for (int i = 0; i < count; i++)
                {
                    var message = new RawBrowserMessage();
                    message.Url = StreamHelper.ReadString(r);
                    message.File = StreamHelper.ReadString(r);
                    message.Method = StreamHelper.ReadString(r);
                    message.RequestBody = StreamHelper.ReadString(r);
                    StreamHelper.ReadStateHeaders(r, out Dictionary<string, List<string>> dict1);
                    StreamHelper.ReadStateHeaders(r, out Dictionary<string, List<string>> dict2);
                    StreamHelper.ReadStateCookies(r, out Dictionary<string, string> dict3);
                    message.RequestHeaders = dict1;
                    message.ResponseHeaders = dict2;
                    message.Cookies = dict3;
                    list[i] = message;
                }
                e.Messages = list;
            }
            return e;
        }
    }
}
