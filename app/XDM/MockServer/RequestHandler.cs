using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace MockServer
{
    public class RequestHandler
    {
        private Regex rx = new Regex(@"bytes=(\d+)-(\d*)",
            RegexOptions.Compiled | RegexOptions.IgnoreCase);
        public string FilePath { get; set; }
        public string Hash { get; set; }
        private byte[] buffer = new byte[8192];
        public CancellationToken CancellationToken { get; set; }
        public Action<long> BytesAction;
        public bool NonResumable { get; set; }
        public Dictionary<string, string> Headers;
        public bool HasContentLength { get; set; } = true;
        public void HandleAsync(HttpListenerRequest request, HttpListenerResponse response)
        {
            Task.Factory.StartNew(() =>
            {
                HandleRequest(request, response);
            });
        }

        private void HandleRequest(HttpListenerRequest request, HttpListenerResponse response)
        {
            var r = new Random();
            var match = rx.Match(request.Headers.Get("Range") ?? "");
            if (!match.Success || !HasContentLength || NonResumable)
            {
                NonResumable = true;
            }

            response.StatusCode = NonResumable ? 200 : 206;

            if (Headers != null)
            {
                foreach (var header in Headers)
                {
                    response.Headers.Add(header.Key, header.Value);
                }
            }

            using Stream fs = new BufferedStream(new FileStream(FilePath, FileMode.Open, FileAccess.Read,
                FileShare.ReadWrite));
            long rem;
            if (NonResumable)
            {
                if (HasContentLength)
                {
                    response.ContentLength64 = fs.Length;
                }
                else
                {
                    response.SendChunked = true;
                }
                rem = fs.Length;
            }
            else
            {
                //Thread.Sleep(r.Next(10, 50));
                long startRange = long.Parse(match.Groups[1].ToString());
                var endRangeStr = match.Groups[2].ToString();
                long endRange = endRangeStr.Length > 0 ? long.Parse(endRangeStr) : fs.Length - 1;

                response.Headers.Add("X-start", match.Groups[1].ToString());
                if (match.Groups.Count > 2)
                    response.Headers.Add("X-End", match.Groups[2].ToString());
                response.Headers.Add("Content-Range", "bytes " + startRange + "-" + endRange + "/" + fs.Length);
                response.ContentLength64 = endRange - startRange + 1;
                fs.Seek(startRange, SeekOrigin.Begin);
                rem = endRange - startRange + 1;
            }
            while (rem > 0 && !CancellationToken.IsCancellationRequested)
            {
                try
                {
                    Thread.Sleep(r.Next(1, NonResumable ? 2 : 30));
                    long k = rem > buffer.Length ? buffer.Length : rem;
                    int x = fs.Read(buffer, 0, (int)k);
                    response.OutputStream.Write(buffer, 0, (int)k);
                    BytesAction(x);
                    rem -= k;
                }
                catch
                {
                    break;
                }

            }
            response.OutputStream.Flush();
            response.OutputStream.Close();
            response.Close();
        }
    }
}
