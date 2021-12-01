using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

namespace NetFX.Polyfill
{
    public static class StreamExtension
    {
        public static void CopyTo(this Stream stream, Stream destination)
        {
#if NET35
            var buffer = new byte[8192];
#else
            var buffer = System.Buffers.ArrayPool<byte>.Shared.Rent(8192);
#endif
            try
            {
                int read;
                while ((read = stream.Read(buffer, 0, buffer.Length)) != 0)
                    destination.Write(buffer, 0, read);
            }
            finally
            {
#if !NET35
                System.Buffers.ArrayPool<byte>.Shared.Return(buffer);
#endif
            }
        }
    }
}
