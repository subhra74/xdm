using HttpServer;
using NUnit.Framework;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace XDM.SystemTests
{
    class NanoServerTests
    {
        [Test]
        public void TestServer()
        {
            var me = new ManualResetEvent(false);
            var server = new NanoServer(IPAddress.Loopback, 5454);
            server.RequestReceived += (a, b) =>
            {
                me.Set();
            };
            server.Start();
            //new Thread(() => server.Start());
            //Thread.Sleep(200000000);
            ////var wr = WebRequest.CreateHttp("http://127.0.0.1:5454/hello");
            ////wr.GetResponse().GetResponseStream().Close();
            //me.WaitOne();
        }
    }
}
