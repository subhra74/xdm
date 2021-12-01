using NUnit.Framework;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using System.Threading.Tasks;

namespace XDM.SystemTests
{
    class TempTests
    {
        [Test]
        public void matroska()
        {
            //var req = (HttpWebRequest)HttpWebRequest.Create("http://www.google.com");
            //req.AllowAutoRedirect = true;
            //req.UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.61 Safari/537.36";
            //req.Headers.Add("cookie", "x=y;a=b;c=d");
            //Console.WriteLine(((HttpWebResponse)req.GetResponse()).ResponseUri);

            var fs = new FileStream(@"D:\Downloads\(5) Love in the Time of Coronavirus _ The Daily Social Distancing Show - YouTube.mkv", FileMode.Open);
            var id = ReadVariableLengthUInt(fs, false);
            Console.WriteLine(id.ToString("X"));
        }

        private ulong ReadVariableLengthUInt(Stream _stream,bool unsetFirstBit = true)
        {
            // Begin loop with byte set to newly read byte
            var first = _stream.ReadByte();
            var length = 0;

            // Begin by counting the bits unset before the highest set bit
            var mask = 0x80;
            for (var i = 0; i < 8; i++)
            {
                // Start at left, shift to right
                if ((first & mask) == mask)
                {
                    length = i + 1;
                    break;
                }
                mask >>= 1;
            }
            if (length == 0)
            {
                return 0;
            }

            // Read remaining big endian bytes and convert to 64-bit unsigned integer.
            var result = (ulong)(unsetFirstBit ? first & (0xFF >> length) : first);
            result <<= --length * 8;
            for (var i = 1; i <= length; i++)
            {
                result |= (ulong)_stream.ReadByte() << (length - i) * 8;
            }
            return result;
        }
    }
}
