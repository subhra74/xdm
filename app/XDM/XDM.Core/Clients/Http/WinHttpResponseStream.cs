using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using static Interop.WinHttp;

namespace XDM.Core.Clients.Http
{
    internal class WinHttpResponseStream : Stream
    {
        private SafeWinHttpHandle hRequest;

        public WinHttpResponseStream(SafeWinHttpHandle hRequest)
        {
            this.hRequest = hRequest;
        }

        public override bool CanRead => true;

        public override bool CanSeek => false;

        public override bool CanWrite => false;

        public override long Length => -1;

        public override long Position { get => throw new NotImplementedException(); set => throw new NotImplementedException(); }

        public override void Flush()
        {

        }

        public override int Read(byte[] buffer, int offset, int count)
        {
            lock (hRequest)
            {
                var pData = Marshal.AllocHGlobal(count);
                try
                {
                    var ret = WinHttpReadData(hRequest, pData, (uint)count, out long lpdwNumberOfBytesAvailable);
                    if (ret && lpdwNumberOfBytesAvailable == 0)
                    {
                        return 0;
                    }
                    else if (lpdwNumberOfBytesAvailable == 0)
                    {
                        throw new IOException("Unable to read from reponse");
                    }
                    Marshal.Copy(pData, buffer, offset, (int)lpdwNumberOfBytesAvailable);
                    return (int)lpdwNumberOfBytesAvailable;
                }
                finally
                {
                    Marshal.FreeHGlobal(pData);
                }
            }
        }

        public override long Seek(long offset, SeekOrigin origin)
        {
            return -1;
        }

        public override void SetLength(long value)
        {

        }

        public override void Write(byte[] buffer, int offset, int count)
        {

        }

        [DllImport("winhttp.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool WinHttpReadData(
            SafeWinHttpHandle requestHandle,
            IntPtr buffer,
            uint bufferSize,
            out long lpdwNumberOfBytesAvailable);
    }
}
