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
        public static void Serialize(ProxyInfo? proxy, BinaryWriter writer)
        {
            writer.Write(proxy.HasValue);
            if (proxy.HasValue)
            {
                writer.Write(proxy.Value.Host ?? string.Empty);
                writer.Write(proxy.Value.Port);
                writer.Write((int)proxy.Value.ProxyType);
                writer.Write(proxy.Value.UserName ?? string.Empty);
                writer.Write(proxy.Value.Password ?? string.Empty);
            }
        }

        public static ProxyInfo? Deserialize(BinaryReader reader)
        {
            if (reader.ReadBoolean())
            {
                return new ProxyInfo
                {
                    Host = Helpers.ReadString(reader),
                    Port = reader.ReadInt32(),
                    ProxyType = (ProxyType)reader.ReadInt32(),
                    UserName = Helpers.ReadString(reader),
                    Password = Helpers.ReadString(reader),
                };
            }
            return null;
        }
    }
}
