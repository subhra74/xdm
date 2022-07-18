//using System;
//using System.Collections.Generic;
//using System.IO;
//using System.Linq;
//using System.Text;
//using System.Threading;

//namespace XDM.Core.Lib.Clients.Http
//{
//    internal class CurlResponseStream : Stream
//    {
//        private CurlSession session;
//        private byte[]? excessData;

//        public CurlResponseStream(CurlSession session)
//        {
//            this.session = session;
//        }

//        public override bool CanRead => true;

//        public override bool CanSeek => false;

//        public override bool CanWrite => false;

//        public override long Length => -1;

//        public override long Position { get => throw new NotImplementedException(); set => throw new NotImplementedException(); }

//        public override void Flush()
//        {

//        }

//        public override int Read(byte[] buffer, int offset, int count)
//        {
//            if (excessData != null)
//            {
//                if (count > excessData.Length)
//                {
//                    var l = excessData.Length;
//                    Array.Copy(excessData, buffer, excessData.Length);
//                    excessData = null;
//                    return l;
//                }
//                else
//                {
//                    Array.Copy(excessData, buffer, count);
//                    var bytes = new byte[excessData.Length - count];
//                    Array.Copy(excessData, count, bytes, 0, bytes.Length);
//                    excessData = bytes;
//                    return count;
//                }
//            }
//            var len = session.ReadData(out byte[] data);
//            if (len == 0) return 0;
//            if (count > len)
//            {
//                Array.Copy(data, buffer, len);
//                return len;
//            }
//            else
//            {
//                Array.Copy(data, buffer, count);
//                excessData = new byte[len - count];
//                Array.Copy(data, count, excessData, 0, excessData.Length);
//                return count;
//            }
//        }

//        public override long Seek(long offset, SeekOrigin origin)
//        {
//            return -1;
//        }

//        public override void SetLength(long value)
//        {

//        }

//        public override void Write(byte[] buffer, int offset, int count)
//        {

//        }
//    }
//}
