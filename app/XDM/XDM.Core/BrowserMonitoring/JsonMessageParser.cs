//using System;
//using System.Collections.Generic;
//using System.Text;
//using System.IO;
//using Newtonsoft.Json;

//namespace XDM.Core.BrowserMonitoring
//{
//    public class JsonMessageParser
//    {
//        private T? ReadProperty<T>(JsonTextReader reader, string name)
//        {
//            if (reader.TokenType == JsonToken.PropertyName && reader.Value?.ToString() == name &&
//                        reader.Read() && reader.Value != null)
//            {
//                return (T)reader.Value;
//            }
//            return default(T);
//        }

//        private bool IsObjectStart(JsonTextReader reader, string name)
//        {
//            return reader.TokenType == JsonToken.PropertyName && reader.Value?.ToString() == name &&
//                        reader.Read() && reader.TokenType == JsonToken.StartObject;
//        }

//        private bool IsListStart(JsonTextReader reader, string name)
//        {
//            return reader.TokenType == JsonToken.PropertyName && reader.Value?.ToString() == name &&
//                        reader.Read() && reader.TokenType == JsonToken.StartArray;
//        }

//        private void SkipUnknownParts(JsonTextReader reader)
//        {
//            if (reader.TokenType == JsonToken.PropertyName && reader.Value != null)
//            {
//                while (reader.Read())
//                {
//                    if (reader.TokenType == JsonToken.StartObject)
//                    {
//                        var n = 1;
//                        while (reader.Read())
//                        {
//                            if (reader.TokenType == JsonToken.EndObject) n--;
//                            if (reader.TokenType == JsonToken.StartObject) n++;
//                            if (n == 0) return;
//                        }
//                    }
//                    else if (reader.TokenType == JsonToken.StartArray)
//                    {
//                        var n = 1;
//                        while (reader.Read())
//                        {
//                            if (reader.TokenType == JsonToken.EndArray) n--;
//                            if (reader.TokenType == JsonToken.StartArray) n++;
//                            if (n == 0) return;
//                        }
//                    }
//                    else if (reader.Value != null)
//                    {
//                        continue;
//                    }
//                }
//            }
//        }

//        private RawBrowserMessage ReadMessageObject(JsonTextReader reader)
//        {
//            var msg = new RawBrowserMessage { Cookies = new(), ResponseHeaders = new(), RequestHeaders = new() };
//            while (reader.Read())
//            {
//                if (reader.TokenType == JsonToken.EndObject) break;
//                var url = ReadProperty<string>(reader, "url");
//                if (url != null)
//                {
//                    msg.Url = url;
//                }
//                if (IsObjectStart(reader, "cookies"))
//                {
//                    while (reader.Read())
//                    {
//                        if (reader.TokenType == JsonToken.EndObject) break;
//                        if (reader.TokenType == JsonToken.PropertyName && reader.Value != null)
//                        {
//                            var cookieName = (string)reader.Value;
//                            if (reader.Read() && reader.TokenType == JsonToken.String)
//                            {
//                                var cookieValue = (string)reader.Value;
//                                msg.Cookies[cookieName] = cookieValue;
//                            }
//                        }
//                    }
//                }

//                if (IsObjectStart(reader, "responseHeaders"))// && IsListStart(reader, "realUA"))
//                {
//                    while (reader.Read())
//                    {
//                        if (reader.TokenType == JsonToken.EndObject) break;
//                        if (reader.TokenType == JsonToken.PropertyName && reader.Value != null)
//                        {
//                            var headerName = (string)reader.Value;
//                            if (IsListStart(reader, headerName))
//                            {
//                                while (reader.Read())
//                                {
//                                    if (reader.TokenType == JsonToken.EndArray) break;
//                                    if (reader.TokenType == JsonToken.String)
//                                    {
//                                        if (msg.ResponseHeaders.TryGetValue(headerName, out var list))
//                                        {
//                                            list.Add((string)reader.Value);
//                                        }
//                                        else
//                                        {
//                                            msg.ResponseHeaders[headerName] = new() { (string)reader.Value };
//                                        }
//                                    }
//                                }
//                            }
//                        }
//                    }
//                }

//                if (IsObjectStart(reader, "requestHeaders"))
//                {
//                    while (reader.Read())
//                    {
//                        if (reader.TokenType == JsonToken.EndObject) break;
//                        if (reader.TokenType == JsonToken.PropertyName && reader.Value != null)
//                        {
//                            var headerName = (string)reader.Value;
//                            if (IsListStart(reader, headerName))
//                            {
//                                while (reader.Read())
//                                {
//                                    if (reader.TokenType == JsonToken.EndArray) break;
//                                    if (reader.TokenType == JsonToken.String)
//                                    {
//                                        if (msg.RequestHeaders.TryGetValue(headerName, out var list))
//                                        {
//                                            list.Add((string)reader.Value);
//                                        }
//                                        else
//                                        {
//                                            msg.RequestHeaders[headerName] = new() { (string)reader.Value };
//                                        }
//                                    }
//                                }
//                            }
//                        }
//                    }
//                }
//                SkipUnknownParts(reader);
//            }
//            return msg;
//        }

//        public RawBrowserMessageEnvelop Parse(Stream stream)
//        {
//            var envelop = new RawBrowserMessageEnvelop();
//            var reader = new JsonTextReader(new StreamReader(stream));
//            if (reader.Read() && reader.TokenType == JsonToken.StartObject)
//            {
//                while (reader.Read())
//                {
//                    if (reader.TokenType == JsonToken.EndObject) break;
//                    var messageType = ReadProperty<string>(reader, "messageType");
//                    if (messageType != null)
//                    {
//                        envelop.MessageType = messageType;
//                    }
//                    var customData = ReadProperty<string>(reader, "customData");
//                    if (customData != null)
//                    {
//                        envelop.CustomData = customData;
//                    }
//                    if (IsObjectStart(reader, "message"))
//                    {
//                        var msg = ReadMessageObject(reader);
//                        envelop.Message = msg;
//                    }
//                    if (IsListStart(reader, "messages"))
//                    {
//                        var list = new List<RawBrowserMessage>();
//                        while (reader.Read())
//                        {
//                            if (reader.TokenType == JsonToken.EndArray) break;
//                            if (reader.TokenType == JsonToken.StartObject)
//                            {
//                                var msg = ReadMessageObject(reader);
//                                list.Add(msg);
//                                envelop.Messages = list.ToArray();
//                            }
//                        }
//                    }
//                    if (IsListStart(reader, "videoIds"))
//                    {
//                        var list = new List<RawBrowserMessage>();
//                        while (reader.Read())
//                        {
//                            if (reader.TokenType == JsonToken.EndArray) break;
//                            if (reader.TokenType == JsonToken.StartObject)
//                            {
//                                var msg = ReadMessageObject(reader);
//                                list.Add(msg);
//                                envelop.Messages = list.ToArray();
//                            }
//                        }
//                    }
//                    SkipUnknownParts(reader);
//                }
//            }
//            return envelop;
//        }
//    }
//}
