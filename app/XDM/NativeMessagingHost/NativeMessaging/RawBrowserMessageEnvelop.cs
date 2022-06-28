//using System;
//using System.Collections.Generic;
//using System.IO;


//namespace NativeMessaging
//{
//    public struct RawBrowserMessageEnvelop
//    {
//        public string MessageType { get; set; }

//        public RawBrowserMessage Message { get; set; }

//        public RawBrowserMessage[] Messages { get; set; }

//        public string[] VideoIds { get; set; }

//        public string CustomData { get; set; }

//        public void Serialize(BinaryWriter w)
//        {
//            RawBrowserMessageEnvelopSerializerV1.Serialize(this, w);
//        }

//        public static RawBrowserMessageEnvelop Deserialize(BinaryReader r)
//        {
//            var version = r.ReadInt32();
//            if (version == 1)
//            {
//                return RawBrowserMessageEnvelopSerializerV1.Deserialize(r);
//            }
//            if (version == Int32.MaxValue) //custom data
//            {
//                Log.Debug("Deseialize custom message");
//                var data = Helpers.ReadString(r);
//                return new RawBrowserMessageEnvelop { MessageType = "custom", CustomData = data };
//            }
//            throw new InvalidDataException($"Version ${version} not supported.");
//        }
//    }

//    internal static class RawBrowserMessageEnvelopSerializerV1
//    {
//        public static void Serialize(RawBrowserMessageEnvelop e, BinaryWriter w)
//        {
//            w.Write(1);
//            w.Write(e.MessageType);
//            w.Write(e.Message != null);
//            if (e.Message != null)
//            {
//                w.Write(e.Message.Url ?? string.Empty);
//                w.Write(e.Message.File ?? string.Empty);
//                w.Write(e.Message.Method ?? string.Empty);
//                w.Write(e.Message.RequestBody ?? string.Empty);
//                Helpers.WriteStateHeaders(e.Message.RequestHeaders, w);
//                Helpers.WriteStateHeaders(e.Message.ResponseHeaders, w);
//                Helpers.WriteStateCookies(e.Message.Cookies, w);
//            }
//            var count = e.VideoIds?.Length ?? 0;
//            w.Write(count);
//            if (e.VideoIds != null && e.VideoIds.Length > 0)
//            {
//                foreach (var item in e.VideoIds)
//                {
//                    w.Write(item);
//                }
//            }
//            w.Write(e.Messages != null);
//            if (e.Messages != null)
//            {
//                count = e.Messages.Length;
//                w.Write(count);
//                foreach (var message in e.Messages)
//                {
//                    w.Write(message.Url ?? string.Empty);
//                    w.Write(message.File ?? string.Empty);
//                    w.Write(message.Method ?? string.Empty);
//                    w.Write(message.RequestBody ?? string.Empty);
//                    Helpers.WriteStateHeaders(message.RequestHeaders, w);
//                    Helpers.WriteStateHeaders(message.ResponseHeaders, w);
//                    Helpers.WriteStateCookies(message.Cookies, w);
//                }
//            }
//        }

//        public static RawBrowserMessageEnvelop Deserialize(BinaryReader r)
//        {
//            var e = new RawBrowserMessageEnvelop { };
//            e.MessageType = Helpers.ReadString(r);
//            if (r.ReadBoolean())
//            {
//                e.Message = new();
//                e.Message.Url = Helpers.ReadString(r);
//                e.Message.File = Helpers.ReadString(r);
//                e.Message.Method = Helpers.ReadString(r);
//                e.Message.RequestBody = Helpers.ReadString(r);
//                Helpers.ReadStateHeaders(r, out Dictionary<string, List<string>> dict1);
//                Helpers.ReadStateHeaders(r, out Dictionary<string, List<string>> dict2);
//                Helpers.ReadStateCookies(r, out Dictionary<string, string> dict3);
//                e.Message.RequestHeaders = dict1;
//                e.Message.ResponseHeaders = dict2;
//                e.Message.Cookies = dict3;
//            }
//            var count = r.ReadInt32();
//            e.VideoIds = new string[count];
//            for (int i = 0; i < count; i++)
//            {
//                e.VideoIds[i] = r.ReadString();
//            }
//            if (r.ReadBoolean())
//            {
//                count = r.ReadInt32();
//                var list = new RawBrowserMessage[count];
//                for (int i = 0; i < count; i++)
//                {
//                    var message = new RawBrowserMessage();
//                    message.Url = Helpers.ReadString(r);
//                    message.File = Helpers.ReadString(r);
//                    message.Method = Helpers.ReadString(r);
//                    message.RequestBody = Helpers.ReadString(r);
//                    Helpers.ReadStateHeaders(r, out Dictionary<string, List<string>> dict1);
//                    Helpers.ReadStateHeaders(r, out Dictionary<string, List<string>> dict2);
//                    Helpers.ReadStateCookies(r, out Dictionary<string, string> dict3);
//                    message.RequestHeaders = dict1;
//                    message.ResponseHeaders = dict2;
//                    message.Cookies = dict3;
//                    list[i] = message;
//                }
//                e.Messages = list;
//            }
//            return e;
//        }
//    }
//}
