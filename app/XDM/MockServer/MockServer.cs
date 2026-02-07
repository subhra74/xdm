using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace MockServer
{
    public class MockServer: IDisposable
    {

        private Dictionary<string, string> fileMap = new Dictionary<string, string>();
        private Dictionary<string, Dictionary<string, string>> headerMap = new Dictionary<string, Dictionary<string, string>>();
        private Dictionary<string, string> fileHashMap = new Dictionary<string, string>();
        public readonly CancellationTokenSource CancellationToken = new CancellationTokenSource();
        private HttpListener listener;
        private long stopAfterBytes = -1;
        private long bytesServed = 0;
        public bool NonResumable { get; set; }
        public bool HasContentLength { get; set; } = true;
        private bool closed = false;

        public string BaseUrl => "http://127.0.0.1:49000/";

        public string GetHash(string id)
        {
            return fileHashMap["/" + id];
        }

        public void StartAsync()
        {
            listener = new HttpListener();
            listener.Prefixes.Add(BaseUrl);
            listener.Start();

            Task.Factory.StartNew(Start);
        }

        public void Stop()
        {
            if (this.closed)
            {
                return;
            }
            this.closed = true;
            this.CancellationToken.Cancel();
            this.listener.Stop();
            foreach (var key in fileMap.Keys)
            {
                var file = fileMap[key];
                try
                {
                    File.Delete(file);
                }
                catch { }
            }
        }

        public void Start()
        {
            
            while (!this.CancellationToken.IsCancellationRequested)
            {
                HttpListenerContext context = listener.GetContext();
                if (fileMap.ContainsKey(context.Request.RawUrl))
                {
                    new RequestHandler
                    {
                        FilePath = fileMap[context.Request.RawUrl],
                        Hash = fileHashMap[context.Request.RawUrl],
                        Headers = headerMap.GetValueOrDefault(context.Request.RawUrl, null),
                        CancellationToken = CancellationToken.Token,
                        NonResumable = this.NonResumable,
                        HasContentLength = this.HasContentLength,
                        BytesAction =
                        x =>
                        {
                            Interlocked.Add(ref bytesServed, x);
                            if (bytesServed >= stopAfterBytes && stopAfterBytes > 0)
                            {
                                this.listener.Stop();
                            }
                        }
                    }.HandleAsync(context.Request, context.Response);
                }
            }

        }

        public (string File, string Hash, long Size) AddMockHandler(string id, long stopAfterPercent = -1, Dictionary<string, string> headers = null, int start = 50, int end = 100, byte[] contents = null, long fixedSize = -1)
        {
            long size;
            long sz = 0;
            string tempFile = Path.Combine(Path.GetTempPath(), id);
            if (contents == null)
            {
                Random random = new Random();
                size = fixedSize > 0 ? fixedSize : random.Next(start, end) * 1024 * 1024;
                sz = size;
                int bufSize = 128 * 1024;
                byte[] b = new byte[bufSize];
                if (stopAfterPercent >= 0)
                {
                    stopAfterBytes = size * stopAfterPercent / 100;
                }

                using (FileStream fs = new FileStream(tempFile, FileMode.Create))
                {
                    while (size > 0)
                    {
                        int rem = (int)Math.Min(bufSize, size);
                        random.NextBytes(b);
                        fs.Write(b, 0, rem);
                        size -= rem;
                    }
                }
            }
            else
            {
                File.WriteAllBytes(tempFile, contents);
                size = contents.Length;
            }

            var hash = "";

            using (SHA256 sha256Hash = SHA256.Create())
            {
                using (FileStream fs = new FileStream(tempFile, FileMode.Open))
                {
                    byte[] buf = new byte[8192];
                    while (true)
                    {
                        int x = fs.Read(buf, 0, buf.Length);
                        if (x == 0) break;
                        sha256Hash.TransformBlock(buf, 0, x, null, 0);
                    }
                    sha256Hash.TransformFinalBlock(new byte[0] { }, 0, 0);
                }

                byte[] bytes = sha256Hash.Hash;
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < bytes.Length; i++)
                {
                    builder.Append(bytes[i].ToString("x2"));
                }
                this.fileHashMap.Add("/" + id, builder.ToString());
                hash = builder.ToString();
            }
            this.fileMap.Add("/" + id, tempFile);
            if (headers != null)
            {
                this.headerMap.Add("/" + id, headers);
            }
            return (File: tempFile, Hash: hash, Size: sz);
        }

        public void Dispose()
        {
            Stop();
        }
    }
}
