using NUnit.Framework;
using Newtonsoft.Json;
using System.IO;
using System;

namespace XDM.Tests
{
    public class Tests
    {
        [SetUp]
        public void Setup()
        {
        }

        [Test]
        public void DeserializeBrowserMessageJsonSuccess()
        {
            Test();
        }

        private T? ReadProperty<T>(JsonTextReader reader, string name)
        {
            if (reader.TokenType == JsonToken.PropertyName && reader.Value.ToString() == name &&
                        reader.Read() && reader.Value != null)
            {
                return (T)reader.Value;
            }
            return default(T);
        }

        private bool IsObjectStart(JsonTextReader reader, string name)
        {
            return reader.TokenType == JsonToken.PropertyName && reader.Value.ToString() == name &&
                        reader.Read() && reader.TokenType == JsonToken.StartObject;
        }

        private bool IsListStart(JsonTextReader reader, string name)
        {
            return reader.TokenType == JsonToken.PropertyName && reader.Value.ToString() == name &&
                        reader.Read() && reader.TokenType == JsonToken.StartArray;
        }

        private void SkipUnknownParts(JsonTextReader reader)
        {
            if (reader.TokenType == JsonToken.PropertyName && reader.Value != null)
            {
                while (reader.Read())
                {
                    if (reader.TokenType == JsonToken.StartObject)
                    {
                        var n = 1;
                        while (reader.Read())
                        {
                            if (reader.TokenType == JsonToken.EndObject) n--;
                            if (reader.TokenType == JsonToken.StartObject) n++;
                            if (n == 0) return;
                        }
                    }
                    else if (reader.TokenType == JsonToken.StartArray)
                    {
                        var n = 1;
                        while (reader.Read())
                        {
                            if (reader.TokenType == JsonToken.EndArray) n--;
                            if (reader.TokenType == JsonToken.StartArray) n++;
                            if (n == 0) return;
                        }
                    }
                    else if (reader.Value != null)
                    {
                        continue;
                    }
                }
            }
        }

        private void ReadMessageObject(JsonTextReader reader)
        {
            while (reader.Read())
            {
                if (reader.TokenType == JsonToken.EndObject) break;
                var url = ReadProperty<string>(reader, "url");
                if (url != null)
                {
                    Console.WriteLine("url: {0}", url);
                }
                if (IsObjectStart(reader, "cookies"))
                {
                    while (reader.Read())
                    {
                        if (reader.TokenType == JsonToken.EndObject) break;
                        if (reader.TokenType == JsonToken.PropertyName && reader.Value != null)
                        {
                            var cookieName = (string)reader.Value;
                            if (reader.Read() && reader.TokenType == JsonToken.String)
                            {
                                var cookieValue = (string)reader.Value;
                                Console.WriteLine("cookieName: {0}, cookieValue: {1}", cookieName, cookieValue);
                            }
                        }
                    }
                }

                if (IsObjectStart(reader, "responseHeaders"))// && IsListStart(reader, "realUA"))
                {
                    while (reader.Read())
                    {
                        if (reader.TokenType == JsonToken.EndObject) break;
                        if (reader.TokenType == JsonToken.PropertyName && reader.Value != null)
                        {
                            var headerName = (string)reader.Value;
                            if (IsListStart(reader, headerName))
                            {
                                while (reader.Read())
                                {
                                    if (reader.TokenType == JsonToken.EndArray) break;
                                    if (reader.TokenType == JsonToken.String)
                                    {
                                        Console.WriteLine("{0}: {1}", headerName, reader.Value);
                                    }
                                }
                            }
                        }
                    }
                }

                if (IsObjectStart(reader, "requestHeaders"))
                {
                    while (reader.Read())
                    {
                        if (reader.TokenType == JsonToken.EndObject) break;
                        if (reader.TokenType == JsonToken.PropertyName && reader.Value != null)
                        {
                            var headerName = (string)reader.Value;
                            if (IsListStart(reader, headerName))
                            {
                                while (reader.Read())
                                {
                                    if (reader.TokenType == JsonToken.EndArray) break;
                                    if (reader.TokenType == JsonToken.String)
                                    {
                                        Console.WriteLine("{0}: {1}", headerName, reader.Value);
                                    }
                                }
                            }
                        }
                    }
                }

                SkipUnknownParts(reader);
            }
        }

        private void Test()
        {
            var reader = new JsonTextReader(new StreamReader(@"C:\Users\subhro\Desktop\message.json"));
            if (reader.Read() && reader.TokenType == JsonToken.StartObject)
            {
                while (reader.Read())
                {
                    if (reader.TokenType == JsonToken.EndObject) break;

                    var messageType = ReadProperty<string>(reader, "messageType");
                    if (messageType != null)
                    {
                        Console.WriteLine("messageType: {0}", messageType);
                    }
                    if (IsObjectStart(reader, "message"))
                    {
                        ReadMessageObject(reader);
                    }
                    if (IsListStart(reader, "messages"))
                    {
                        while (reader.Read())
                        {
                            if (reader.TokenType == JsonToken.EndArray) break;
                            if (reader.TokenType == JsonToken.StartObject)
                            {
                                ReadMessageObject(reader);
                            }
                        }
                    }
                    SkipUnknownParts(reader);
                }
            }
        }
    }
}