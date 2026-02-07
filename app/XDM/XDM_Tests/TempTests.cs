
using NUnit.Framework;
using System;
using XDM.Core.Lib.Util;

namespace XDM.SystemTests
{
    class TempTests
    {
        [TestCase(12, 3, 12, 3, false)]
        [TestCase(0, 59, 12, 59, true)]
        [TestCase(23, 59, 11, 59, false)]
        [TestCase(16, 39, 4, 39, false)]
        public void TimeTest1(int h1, int m1, int h2, int m2, bool am)
        {
            TimeHelper.ConvertH24ToH12(new TimeSpan(h1, m1, 0), out int hrs, out int mi, out bool ampm);
            Assert.AreEqual(hrs, h2);
            Assert.AreEqual(mi, m2);
            Assert.AreEqual(ampm, am);
        }

        [TestCase(12, 3, false, 12, 3)]
        [TestCase(12, 59, true, 0, 59)]
        [TestCase(11, 59, false, 23, 59)]
        [TestCase(4, 39, false, 16, 39)]
        public void TimeTest2(int h1, int m1, bool am, int h2, int m2)
        {
            var time = TimeHelper.ConvertH12ToH24(h1, m1, am);
            Assert.AreEqual(time.Hours, h2);
            Assert.AreEqual(time.Minutes, m2);
        }

        //[Test]
        //public void matroska()
        //{
        //    //var req = (HttpWebRequest)HttpWebRequest.Create("http://www.google.com");
        //    //req.AllowAutoRedirect = true;
        //    //req.UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.61 Safari/537.36";
        //    //req.Headers.Add("cookie", "x=y;a=b;c=d");
        //    //Console.WriteLine(((HttpWebResponse)req.GetResponse()).ResponseUri);

        //    var fs = new FileStream(@"D:\Downloads\(5) Love in the Time of Coronavirus _ The Daily Social Distancing Show - YouTube.mkv", FileMode.Open);
        //    var id = ReadVariableLengthUInt(fs, false);
        //    Console.WriteLine(id.ToString("X"));
        //}

        //private ulong ReadVariableLengthUInt(Stream _stream,bool unsetFirstBit = true)
        //{
        //    // Begin loop with byte set to newly read byte
        //    var first = _stream.ReadByte();
        //    var length = 0;

        //    // Begin by counting the bits unset before the highest set bit
        //    var mask = 0x80;
        //    for (var i = 0; i < 8; i++)
        //    {
        //        // Start at left, shift to right
        //        if ((first & mask) == mask)
        //        {
        //            length = i + 1;
        //            break;
        //        }
        //        mask >>= 1;
        //    }
        //    if (length == 0)
        //    {
        //        return 0;
        //    }

        //    // Read remaining big endian bytes and convert to 64-bit unsigned integer.
        //    var result = (ulong)(unsetFirstBit ? first & (0xFF >> length) : first);
        //    result <<= --length * 8;
        //    for (var i = 1; i <= length; i++)
        //    {
        //        result |= (ulong)_stream.ReadByte() << (length - i) * 8;
        //    }
        //    return result;
        //}
    }
}
