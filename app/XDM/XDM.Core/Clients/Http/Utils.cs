using System;
using System.Collections.Generic;
using System.Text;

namespace XDM.Core.Clients.Http
{
    internal static class Utils
    {
        internal static bool IsCompressed(string? header)
        {
            if (!string.IsNullOrEmpty(header))
            {
                return (header.Contains("gzip") || header.Contains("deflate"));
            }
            return false;
        }
    }
}
