
//using System;
//using System.Collections.Generic;
//using System.IO;
//using System.Linq;
//using System.Net;
//using System.Text;
//using XDM.Core.Lib.Common;
//using TraceLog;
//using System.Runtime.InteropServices;

//namespace XDM.Core.Lib.Clients.Http
//{
//    internal class WinInetSession : IHttpSession
//    {
//        private IntPtr hConnect, hRequest;
//        private InternetStatusCallback callback;

//        private Uri responseUri;
//        private HttpStatusCode statusCode;
//        private string? statusDescription;
//        private long contentLength;
//        private string? contentType;
//        private string? contentDispositionFileName;
//        private DateTime lastModified = DateTime.Now;
//        private long rangeStart = -1, rangeEnd = -1;
//        private Dictionary<string, List<string>>? headers = null;
//        private Dictionary<string, string>? cookies = null;
//        private Stream responseStream;

//        public Dictionary<string, List<string>>? Headers => this.headers;
//        public Dictionary<string, string>? Cookies => this.cookies;

//        public IntPtr ConnectHandle => hConnect;
//        public IntPtr RequestHandle => hRequest;

//        public WinInetSession(
//            Uri responseUri,
//            IntPtr hConnect,
//            IntPtr hRequest,
//            Dictionary<string, List<string>>? headers,
//            Dictionary<string, string>? cookies)
//        {
//            this.responseUri = responseUri;
//            this.hConnect = hConnect;
//            this.hRequest = hRequest;
//            this.callback = new(StatusCallback);
//            var oldCallback = InternetSetStatusCallback(hRequest, this.callback);
//            //IntPtr oldCallback = WinHttpSetStatusCallback(
//            //    hRequest,
//            //    this.callback,
//            //    WINHTTP_CALLBACK_FLAG_REDIRECT,
//            //    IntPtr.Zero);
//            //if (oldCallback == IntPtr.)
//            //{
//            //    int lastError = Marshal.GetLastWin32Error();
//            //    if (lastError != ERROR_INVALID_HANDLE) // Ignore error if handle was already closed.
//            //    {
//            //        throw new IOException(nameof(Interop.WinHttp.WinHttpSetStatusCallback));
//            //    }
//            //}
//            this.headers = headers;
//            this.cookies = cookies;
//            this.responseStream = new WinInetResponseStream(this.hRequest);
//        }

//        //private void InternetStatusCallback(HINTERNET hInternet, IntPtr dwContext, InternetStatus dwInternetStatus, IntPtr lpvStatusInformation, uint dwStatusInformationLength)

//        private void StatusCallback(
//           IntPtr handle,
//           IntPtr context,
//           InternetStatus internetStatus,
//           IntPtr statusInformation,
//           uint statusInformationLength)
//        {
//            if (internetStatus == InternetStatus.INTERNET_STATUS_REDIRECT)
//            {
//                responseUri = new Uri(Marshal.PtrToStringUni(statusInformation)!);
//                Log.Debug("Redirected to: " + responseUri);
//            }
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
//            lock (this)
//            {
//                WinInetClient.InternetCloseHandle(this.RequestHandle);
//                WinInetClient.InternetCloseHandle(this.ConnectHandle);
//            }
//        }

//        public void AddRange(long range)
//        {
//            this.rangeStart = range;
//        }

//        public void AddRange(long start, long end)
//        {
//            this.rangeStart = start;
//            this.rangeEnd = end;
//        }

//        public void Close()
//        {
//            lock (this)
//            {
//                WinInetClient.InternetCloseHandle(this.RequestHandle);
//                WinInetClient.InternetCloseHandle(this.ConnectHandle);
//            }
//        }

//        public void Dispose()
//        {
//            lock (this)
//            {
//                WinInetClient.InternetCloseHandle(this.RequestHandle);
//                WinInetClient.InternetCloseHandle(this.ConnectHandle);
//            }
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
//#if NET35
//            var buf = new byte[8192];
//#else
//            var buf = System.Buffers.ArrayPool<byte>.Shared.Rent(8192);
//#endif
//            try
//            {
//                var sourceStream = responseStream;
//                var ms = new MemoryStream();
//                while (!cancellationToken.IsCancellationRequested)
//                {
//                    var x = sourceStream.Read(buf, 0, buf.Length);
//                    if (x == 0)
//                    {
//                        break;
//                    }
//                    ms.Write(buf, 0, x);
//                    cancellationToken.ThrowIfCancellationRequested();
//                }
//                return Encoding.UTF8.GetString(ms.ToArray());
//            }
//            finally
//            {
//#if !NET35
//                System.Buffers.ArrayPool<byte>.Shared.Return(buf);
//#endif
//            }
//        }

//        internal void SetHeaders(string headers)
//        {
//            var lines = headers.Split('\r', '\n');
//            var status = lines[0];
//            this.statusCode = (HttpStatusCode)Int32.Parse(status.Split(' ')[1].Trim());
//            this.statusDescription = status.Split(' ')[2];
//            foreach (var line in lines)
//            {
//                var index = line.IndexOf(':');
//                if (index > 0)
//                {
//                    var key = line.Substring(0, index).ToLowerInvariant();
//                    var value = line.Substring(index + 1).Trim();
//                    switch (key)
//                    {
//                        case "content-length":
//                            this.contentLength = Int64.Parse(value);
//                            break;
//                        case "content-type":
//                            this.contentType = value;
//                            break;
//                        case "content-disposition":
//                            this.contentDispositionFileName = WebRequestExtensions.GetContentDispositionFileName(value);
//                            break;
//                        default:
//                            break;
//                    }
//                }
//            }
//        }

//        [DllImport("wininet.dll", CharSet = CharSet.Unicode, SetLastError = true)]
//        public extern static IntPtr InternetSetStatusCallback(IntPtr hInternet, InternetStatusCallback lpfnInternetCallback);

//        public delegate void InternetStatusCallback(IntPtr hInternet, IntPtr dwContext, InternetStatus dwInternetStatus, IntPtr lpvStatusInformation, uint dwStatusInformationLength);
//    }

//    public enum InternetStatus
//    {
//        //
//        // Summary:
//        //     Looking up the IP address of the name contained in lpvStatusInformation. The
//        //     lpvStatusInformation parameter points to a PCTSTR containing the host name.
//        INTERNET_STATUS_RESOLVING_NAME = 10,
//        //
//        // Summary:
//        //     Successfully found the IP address of the name contained in lpvStatusInformation.
//        //     The lpvStatusInformation parameter points to a PCTSTR containing the host name.
//        INTERNET_STATUS_NAME_RESOLVED = 11,
//        //
//        // Summary:
//        //     Connecting to the socket address (SOCKADDR) pointed to by lpvStatusInformation.
//        INTERNET_STATUS_CONNECTING_TO_SERVER = 20,
//        //
//        // Summary:
//        //     Successfully connected to the socket address (SOCKADDR) pointed to by lpvStatusInformation.
//        INTERNET_STATUS_CONNECTED_TO_SERVER = 21,
//        //
//        // Summary:
//        //     Sending the information request to the server. The lpvStatusInformation parameter
//        //     is NULL.
//        INTERNET_STATUS_SENDING_REQUEST = 30,
//        //
//        // Summary:
//        //     Successfully sent the information request to the server. The lpvStatusInformation
//        //     parameter points to a DWORD value that contains the number of bytes sent.
//        INTERNET_STATUS_REQUEST_SENT = 31,
//        //
//        // Summary:
//        //     Waiting for the server to respond to a request. The lpvStatusInformation parameter
//        //     is NULL.
//        INTERNET_STATUS_RECEIVING_RESPONSE = 40,
//        //
//        // Summary:
//        //     Successfully received a response from the server.
//        INTERNET_STATUS_RESPONSE_RECEIVED = 41,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_STATUS_CTL_RESPONSE_RECEIVED = 42,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_STATUS_PREFETCH = 43,
//        //
//        // Summary:
//        //     Closing the connection to the server. The lpvStatusInformation parameter is NULL.
//        INTERNET_STATUS_CLOSING_CONNECTION = 50,
//        //
//        // Summary:
//        //     Successfully closed the connection to the server. The lpvStatusInformation parameter
//        //     is NULL.
//        INTERNET_STATUS_CONNECTION_CLOSED = 51,
//        //
//        // Summary:
//        //     Used by InternetConnect to indicate it has created the new handle. This lets
//        //     the application call InternetCloseHandle from another thread, if the connect
//        //     is taking too long. The lpvStatusInformation parameter contains the address of
//        //     an HINTERNET handle.
//        INTERNET_STATUS_HANDLE_CREATED = 60,
//        //
//        // Summary:
//        //     This handle value has been terminated. pvStatusInformation contains the address
//        //     of the handle being closed. The lpvStatusInformation parameter contains the address
//        //     of the handle being closed.
//        INTERNET_STATUS_HANDLE_CLOSING = 70,
//        //
//        // Summary:
//        //     Notifies the client application that a proxy has been detected.
//        INTERNET_STATUS_DETECTING_PROXY = 80,
//        //
//        // Summary:
//        //     An asynchronous operation has been completed. The lpvStatusInformation parameter
//        //     contains the address of an INTERNET_ASYNC_RESULT structure.
//        INTERNET_STATUS_REQUEST_COMPLETE = 100,
//        //
//        // Summary:
//        //     An HTTP request is about to automatically redirect the request. The lpvStatusInformation
//        //     parameter points to the new URL. At this point, the application can read any
//        //     data returned by the server with the redirect response and can query the response
//        //     headers. It can also cancel the operation by closing the handle. This callback
//        //     is not made if the original request specified INTERNET_FLAG_NO_AUTO_REDIRECT.
//        INTERNET_STATUS_REDIRECT = 110,
//        //
//        // Summary:
//        //     Received an intermediate (100 level) status code message from the server.
//        INTERNET_STATUS_INTERMEDIATE_RESPONSE = 120,
//        //
//        // Summary:
//        //     The request requires user input to be completed.
//        INTERNET_STATUS_USER_INPUT_REQUIRED = 140,
//        //
//        // Summary:
//        //     Moved between a secure (HTTPS) and a nonsecure (HTTP) site. The user must be
//        //     informed of this change; otherwise, the user is at risk of disclosing sensitive
//        //     information involuntarily. When this flag is set, the lpvStatusInformation parameter
//        //     points to a status DWORD that contains additional flags.
//        INTERNET_STATUS_STATE_CHANGE = 200,
//        //
//        // Summary:
//        //     Indicates the number of cookies that were either sent or suppressed, when a request
//        //     is sent. The lpvStatusInformation parameter is a DWORD with the number of cookies
//        //     sent or suppressed.
//        INTERNET_STATUS_COOKIE_SENT = 320,
//        //
//        // Summary:
//        //     Indicates the number of cookies that were accepted, rejected, downgraded (changed
//        //     from persistent to session cookies), or leashed (will be sent out only in 1st
//        //     party context). The lpvStatusInformation parameter is a DWORD with the number
//        //     of cookies received.
//        INTERNET_STATUS_COOKIE_RECEIVED = 321,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_STATUS_PRIVACY_IMPACTED = 324,
//        //
//        // Summary:
//        //     The response has a P3P header in it.
//        INTERNET_STATUS_P3P_HEADER = 325,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_STATUS_P3P_POLICYREF = 326,
//        //
//        // Summary:
//        //     Retrieving content from the cache. Contains data about past cookie events for
//        //     the URL such as if cookies were accepted, rejected, downgraded, or leashed. The
//        //     lpvStatusInformation parameter is a pointer to an InternetCookieHistory structure.
//        INTERNET_STATUS_COOKIE_HISTORY = 327
//    }
//}

