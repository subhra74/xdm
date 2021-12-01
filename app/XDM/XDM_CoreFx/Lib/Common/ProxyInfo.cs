using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;


namespace XDM.Core.Lib.Common
{
    public struct ProxyInfo
    {
        public ProxyType ProxyType { get; set; }
        public string Host { get; set; }
        public int Port { get; set; }
        public string UserName { get; set; }
        public string Password { get; set; }
    }

    public enum ProxyType
    {
        System, Direct, Custom
    }
}
