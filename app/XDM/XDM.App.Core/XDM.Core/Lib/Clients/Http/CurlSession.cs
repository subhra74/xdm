//using System;
//using System.Collections.Generic;
//using System.IO;
//using System.Linq;
//using System.Net;
//using System.Runtime.InteropServices;
//using System.Text;
//using System.Threading;
//using TraceLog;
//using XDM.Core.Interop.CURL;
//using XDM.Core.Lib.Common;

//namespace XDM.Core.Lib.Clients.Http
//{
//    internal class CurlSession : IHttpSession
//    {
//        private IntPtr easyHandle;
//        private bool needData = false;
//        private AutoResetEvent dataWaitHandle = new(false);
//        private AutoResetEvent headerWaitHandle = new(false);
//        private bool dataStarted = false, error = false, finished = false;
//        private byte[] data;
//        private StringBuilder responseHeaderBuffer = new();
//        private CurlNative.CurlCallback cbHeader, cbData;
//        private CurlResponseStream responseStream;

//        private Uri responseUri;
//        private HttpStatusCode statusCode;
//        private string? statusDescription;
//        private long contentLength;
//        private string? contentType;
//        private string? contentDispositionFileName;
//        private DateTime lastModified = DateTime.Now;
//        private long rangeStart = -1, rangeEnd = -1;

//        public CurlSession(Uri uri, string method,
//            Dictionary<string, List<string>>? headers = null,
//            Dictionary<string, string>? cookies = null,
//            AuthenticationInfo? authentication = null)
//        {
//            cbHeader = new CurlNative.CurlCallback(HeaderHandler);
//            cbData = new CurlNative.CurlCallback(DataHandler);
//            var easy = CurlNative.curl_easy_init();
//            var ret = 0;
//            ret = CurlNative.curl_easy_setopt(easy, CurlNative.CURLOPT_URL, uri.ToString());// "https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-4.3.2-essentials_build.zip");// "https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-4.3.2-essentials_build.zip");
//            ret = CurlNative.curl_easy_setopt(easy, CurlNative.CURLOPT_FOLLOWLOCATION, 1);
//            ret = CurlNative.curl_easy_setopt(easy, CurlNative.CURLOPT_SSL_VERIFYPEER, 0L);
//            ret = CurlNative.curl_easy_setopt(easy, CurlNative.CURLOPT_HEADERFUNCTION, cbHeader);
//            ret = CurlNative.curl_easy_setopt(easy, CurlNative.CURLOPT_WRITEFUNCTION, cbData);
//            this.responseStream = new CurlResponseStream(this);
//        }

//        public string? ContentType => this.contentType;

//        public string? ContentDispositionFileName => this.contentDispositionFileName;

//        public long ContentLength => this.contentLength;

//        public DateTime LastModified => this.lastModified;

//        public HttpStatusCode StatusCode => this.statusCode;

//        public Uri ResponseUri => responseUri;

//        public long RangeEnd => rangeEnd;

//        public long RangeStart => rangeStart;



//        public void Abort()
//        {
//            Close();
//        }

//        public void AddRange(long range)
//        {
//            throw new NotImplementedException();
//        }

//        public void AddRange(long start, long end)
//        {
//            throw new NotImplementedException();
//        }

//        public void Close()
//        {
//            lock (this)
//            {
//                finished = true;
//                dataWaitHandle.Set();
//                CurlNative.curl_easy_cleanup(easyHandle);
//                easyHandle = IntPtr.Zero;
//            }
//        }

//        public void Dispose()
//        {
//            Close();
//        }

//        public void EnsureSuccessStatusCode()
//        {
//            if (statusCode != HttpStatusCode.OK && statusCode != HttpStatusCode.PartialContent)
//            {
//                throw new HttpException(statusDescription, null, statusCode);
//            }
//        }

//        public Stream GetResponseStream()
//        {
//            return this.responseStream;
//        }

//        public string ReadAsString(CancelFlag cancellationToken)
//        {
//            throw new NotImplementedException();
//        }

//        private void ProcessHeaders()
//        {
//            string statusLine = null;
//            string location = null;
//            var headers = responseHeaderBuffer.ToString().Replace("\n", "").Split('\r');
//            string headerName = null, headerValue = null;
//            bool statusLineExpected = true;
//            foreach (var header in headers)
//            {
//                //special case: header folding
//                if (header.StartsWith(" ") || header.StartsWith("\t"))
//                {
//                    headerValue += header;
//                    continue;
//                }
//                if (statusLineExpected)
//                {
//                    statusLine = header;
//                    statusLineExpected = false;
//                }
//                if (header.Length == 0)
//                {
//                    statusLineExpected = true;
//                    continue;
//                }
//                var sep = header.IndexOf(':');
//                if (sep < 1)
//                {
//                    Log.Debug($"Invalid header: {header}");
//                    continue;
//                }

//                var key = header.Substring(0, sep).ToLowerInvariant();
//                var value = header.Substring(sep + 1).Trim();
//                switch (key)
//                {
//                    case "content-length":
//                        this.contentLength = Int64.Parse(value);
//                        break;
//                    case "content-type":
//                        this.contentType = value;
//                        break;
//                    case "location":
//                        this.responseUri = new Uri(value);
//                        break;
//                    case "content-disposition":
//                        this.contentDispositionFileName = WebRequestExtensions.GetContentDispositionFileName(value);
//                        break;
//                    default:
//                        break;
//                }
//            }
//        }

//        private uint HeaderHandler(IntPtr data, uint size, uint nmemb, IntPtr userdata)
//        {
//            if (dataStarted) return size * nmemb;
//            var managedArray = new byte[size * nmemb];
//            Marshal.Copy(data, managedArray, 0, (int)(size * nmemb));
//            var str = Encoding.UTF8.GetString(managedArray, 0, (int)(size * nmemb));
//            Console.WriteLine($"Curl-Header: {str}");
//            responseHeaderBuffer.Append(str);
//            return size * nmemb;
//        }

//        private uint DataHandler(IntPtr data, uint size, uint nmemb, IntPtr userdata)
//        {
//            if (!dataStarted)
//            {
//                dataStarted = true;
//                ProcessHeaders();
//                headerWaitHandle.Set();
//            }

//            var dataRequired = false;
//            lock (this)
//            {
//                dataRequired = needData;
//            }
//            if (dataRequired)
//            {
//                var managedArray = new byte[size * nmemb];
//                Marshal.Copy(data, managedArray, 0, (int)(size * nmemb));
//                this.data = managedArray;
//                dataWaitHandle.Set();
//                return size * nmemb;
//            }
//            return CurlNative.CURL_WRITEFUNC_PAUSE;
//        }

//        internal int ReadData(out byte[] data)
//        {
//            needData = true;
//            if (ResumeCallback())
//            {
//                lock (this)
//                {
//                    needData = false;
//                }
//                data = this.data;
//                return this.data.Length;
//            }
//            else
//            {
//                if (error)
//                {
//                    throw new IOException("Error from curl");
//                }
//                if (finished)
//                {
//                    data = new byte[0];
//                    return 0;
//                }
//                else
//                {
//                    throw new IOException("Neither error not finished");
//                }
//            }
//        }

//        public bool ResumeCallback()
//        {
//            if (easyHandle == IntPtr.Zero)
//            {
//                return false;
//            }
//            dataWaitHandle.WaitOne();
//            if (easyHandle == IntPtr.Zero)
//            {
//                return false;
//            }
//            return true;
//        }
//    }
//}
