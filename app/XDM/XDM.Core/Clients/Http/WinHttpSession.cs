using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;
using TraceLog;
using XDM.Core;
using static Interop.WinHttp;

namespace XDM.Core.Clients.Http
{
    internal class WinHttpSession : IHttpSession
    {
        private SafeWinHttpHandle hConnect, hRequest;
        private WINHTTP_STATUS_CALLBACK callback;
        private Uri responseUri;
        private HttpStatusCode statusCode;
        private string? statusDescription;
        private long contentLength;
        private string contentRange;
        private string? contentType;
        private string? contentDispositionFileName;
        private DateTime lastModified = DateTime.Now;
        private long rangeStart = -1, rangeEnd = -1;
        private Dictionary<string, List<string>>? headers = null;
        private string? cookies = null;
        private Stream responseStream;
        private IntPtr? postData;
        private int postDataSize;

        public Dictionary<string, List<string>>? Headers => this.headers;
        public string? Cookies => this.cookies;

        public SafeWinHttpHandle ConnectHandle => hConnect;
        public SafeWinHttpHandle RequestHandle => hRequest;
        public IntPtr? PostData => postData;
        public int PostDataSize => postDataSize;

        public WinHttpSession(
            Uri responseUri,
            SafeWinHttpHandle hConnect,
            SafeWinHttpHandle hRequest,
            Dictionary<string, List<string>>? headers,
            string? cookies,
            byte[]? postData = null)
        {
            this.responseUri = responseUri;
            this.hConnect = hConnect;
            this.hRequest = hRequest;
            this.callback = new(WinHttpCallback);
            IntPtr oldCallback = WinHttpSetStatusCallback(
                hRequest,
                this.callback,
                WINHTTP_CALLBACK_FLAG_REDIRECT,
                IntPtr.Zero);
            if (oldCallback == new IntPtr(WINHTTP_INVALID_STATUS_CALLBACK))
            {
                int lastError = Marshal.GetLastWin32Error();
                if (lastError != ERROR_INVALID_HANDLE) // Ignore error if handle was already closed.
                {
                    throw new IOException(nameof(WinHttpSetStatusCallback));
                }
            }
            this.headers = headers;
            this.cookies = cookies;
            if (postData != null && postData.Length > 0)
            {
                this.postData = Marshal.AllocHGlobal(postData.Length);
                Marshal.Copy(postData, 0, this.postData.Value, postData.Length);
                this.postDataSize = postData.Length;
            }
            this.responseStream = new WinHttpResponseStream(this.hRequest);
        }

        private void WinHttpCallback(
           IntPtr handle,
           IntPtr context,
           uint internetStatus,
           IntPtr statusInformation,
           uint statusInformationLength)
        {
            if (internetStatus == WINHTTP_CALLBACK_STATUS_REDIRECT)
            {
                responseUri = new Uri(Marshal.PtrToStringUni(statusInformation)!);
                Log.Debug("Redirected to: " + responseUri);
            }
        }

        public string? ContentType => this.contentType;

        public string? ContentDispositionFileName => this.contentDispositionFileName;

        public long ContentLength => this.contentLength;

        public DateTime LastModified => this.lastModified;

        public HttpStatusCode StatusCode => this.statusCode;

        public Uri ResponseUri => responseUri;

        public long RangeEnd => rangeEnd;

        public long RangeStart => rangeStart;

        public void Abort()
        {
            Close();
        }

        public void AddRange(long range)
        {
            this.rangeStart = range;
        }

        public void AddRange(long start, long end)
        {
            this.rangeStart = start;
            this.rangeEnd = end;
        }

        public void Close()
        {
            lock (this)
            {
                this.RequestHandle.Close();
                this.ConnectHandle.Close();
                if (this.postData.HasValue)
                {
                    Marshal.FreeHGlobal(this.postData.Value);
                }
            }
        }

        public void Dispose()
        {
            Close();
        }

        public void EnsureSuccessStatusCode()
        {
            if (statusCode != HttpStatusCode.OK && statusCode != HttpStatusCode.PartialContent)
            {
                throw new HttpException(statusDescription, null, statusCode);
            }
        }

        public Stream GetResponseStream()
        {
            return this.responseStream;
        }

        public string ReadAsString(CancelFlag cancellationToken)
        {
#if NET35
            var buf = new byte[8192];
#else
            var buf = System.Buffers.ArrayPool<byte>.Shared.Rent(8192);
#endif
            try
            {
                var sourceStream = responseStream;
                var ms = new MemoryStream();
                while (!cancellationToken.IsCancellationRequested)
                {
                    var x = sourceStream.Read(buf, 0, buf.Length);
                    if (x == 0)
                    {
                        break;
                    }
                    ms.Write(buf, 0, x);
                    cancellationToken.ThrowIfCancellationRequested();
                }
                return Encoding.UTF8.GetString(ms.ToArray());
            }
            finally
            {
#if !NET35
                System.Buffers.ArrayPool<byte>.Shared.Return(buf);
#endif
            }
        }

        internal void SetHeaders(string headers)
        {
            var lines = headers.Split('\r', '\n');
            var status = lines[0];
            this.statusCode = (HttpStatusCode)Int32.Parse(status.Split(' ')[1].Trim());
            this.statusDescription = status.Split(' ')[2];
            foreach (var line in lines)
            {
                var index = line.IndexOf(':');
                if (index > 0)
                {
                    var key = line.Substring(0, index).ToLowerInvariant();
                    var value = line.Substring(index + 1).Trim();
                    switch (key)
                    {
                        case "content-length":
                            this.contentLength = Int64.Parse(value);
                            break;
                        case "content-range":
                            this.contentRange = value;
                            break;
                        case "content-type":
                            this.contentType = value;
                            break;
                        case "content-disposition":
                            this.contentDispositionFileName = WebRequestExtensions.GetContentDispositionFileName(value);
                            break;
                        default:
                            break;
                    }
                }
            }
        }

        public long GetTotalLengthFromContentRange()
        {
            if (!string.IsNullOrEmpty(contentRange))
            {
                return WebRequestExtensions.ContentLengthFromContentRange(contentRange);
            }
            return -1;
        }
    }
}
