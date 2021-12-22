using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using XDM.Core.Lib.Util;

namespace XDM.Core.Lib.Common
{
    public static class ProxyInfoSerializer
    {
        public static void Serialize(ProxyInfo proxy, BinaryWriter w)
        {
            w.Write(5);
            w.Write(nameof(proxy.Host));
            w.Write(STRING);
            w.Write(proxy.Host);
            w.Write(nameof(proxy.Port));
            w.Write(INT);
            w.Write(proxy.Port);
            w.Write(nameof(proxy.ProxyType));
            w.Write(INT);
            w.Write((int)proxy.ProxyType);
            w.Write(nameof(proxy.UserName));
            w.Write(STRING);
            w.Write(proxy.Password);
            w.Write(nameof(proxy.Password));
            w.Write(STRING);
            w.Write(proxy.Password);
        }

        //public static ProxyInfo? Deserialize(BinaryReader reader)
        //{
        //    if (reader.ReadBoolean())
        //    {
        //        return new ProxyInfo
        //        {
        //            Host = Helpers.ReadString(reader),
        //            Port = reader.ReadInt32(),
        //            ProxyType = (ProxyType)reader.ReadInt32(),
        //            UserName = Helpers.ReadString(reader),
        //            Password = Helpers.ReadString(reader),
        //        };
        //    }
        //    return null;
        //}

        private const int INT = 0, STRING = 1, BOOL = 2;

        public static ProxyInfo Deserialize(BinaryReader r)
        {
            var proxy = new ProxyInfo();
            var fieldCount = r.ReadInt16();
            for (int i = 0; i < fieldCount; i++)
            {
                var fieldName = r.ReadString();
                var fieldType = r.ReadByte();
                switch (fieldName)
                {
                    case "Host":
                        proxy.Host = r.ReadString();
                        break;
                    case "Port":
                        proxy.Port = r.ReadInt32();
                        break;
                    case "ProxyType":
                        proxy.ProxyType = (ProxyType)r.ReadInt32();
                        break;
                    case "UserName":
                        proxy.UserName = r.ReadString();
                        break;
                    case "Password":
                        proxy.Password = r.ReadString();
                        break;
                    default:
                        switch (fieldType)
                        {
                            case INT:
                                r.ReadInt32();
                                break;
                            case STRING:
                                r.ReadString();
                                break;
                            case BOOL:
                                r.ReadBoolean();
                                break;
                            default:
                                throw new IOException($"Unsupported type: '{fieldType}'");
                        }
                        break;
                }
            }
            return proxy;
        }
    }
}
