﻿using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using XDM.Core.Util;

namespace XDM.Core
{
    public static class ProxyInfoSerializer
    {
        public static void Serialize(ProxyInfo proxy, BinaryWriter w)
        {
            SerializationHelper.SerializeProxyInfo(proxy, w);
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

        
        public static ProxyInfo Deserialize(BinaryReader r)
        {
            return SerializationHelper.DeserializeProxyInfo(r);
        }
    }
}