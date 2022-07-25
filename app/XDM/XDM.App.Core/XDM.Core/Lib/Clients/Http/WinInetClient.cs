
//using System;
//using System.Collections.Generic;
//using System.IO;
//using System.Linq;
//using System.Runtime.InteropServices;
//using System.Text;
//using TraceLog;
//using XDM.Core;

//namespace XDM.Core.Clients.Http
//{
//    internal class WinInetClient : IHttpClient
//    {
//        private IntPtr? sessionHandle;
//        private bool disposed;

//        public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(100);

//        private HttpRequest CreateRequest(
//            Uri uri,
//            string method,
//            Dictionary<string, List<string>>? headers = null,
//            Dictionary<string, string>? cookies = null,
//            AuthenticationInfo? authentication = null,
//            byte[]? body = null)
//        {
//            if (disposed)
//            {
//                throw new ObjectDisposedException("HttpWebRequestClient");
//            }

//            lock (this)
//            {
//                Log.Debug("Wininet init");
//                if (this.sessionHandle == null)
//                {
//                    sessionHandle = InternetOpen(
//                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.81 Safari/537.36",
//                                InternetOpenType.INTERNET_OPEN_TYPE_PRECONFIG, null, null, InternetApiFlags.INTERNET_FLAG_DONT_CACHE |
//                                InternetApiFlags.INTERNET_FLAG_NO_CACHE_WRITE);
//                    if (sessionHandle == IntPtr.Zero)
//                    {
//                        throw new IOException($"InternetOpen failed: {Marshal.GetLastWin32Error()}");
//                    }
//                    SetGlobalOption(InternetOptionFlags.INTERNET_OPTION_MAX_CONNS_PER_SERVER, 100);
//                    SetGlobalOption(InternetOptionFlags.INTERNET_OPTION_MAX_CONNS_PER_PROXY, 100);
//                    SetGlobalOption(InternetOptionFlags.INTERNET_OPTION_MAX_CONNS_PER_1_0_SERVER, 100);
//                    //int optionData = 100;
//                    //var intPtr = Marshal.AllocHGlobal(4);
//                    //Marshal.StructureToPtr(optionData, intPtr, true);
//                    //if (!InternetSetOption(IntPtr.Zero, InternetOptionFlags.INTERNET_OPTION_MAX_CONNS_PER_SERVER, intPtr, 4))
//                    //{
//                    //    Log.Debug("Unable to set connection limit " + Marshal.GetLastWin32Error());
//                    //}
//                    //else
//                    //{
//                    //    Log.Debug("Max connection limit: " + optionData);
//                    //}
//                    //Marshal.FreeHGlobal(intPtr);
//                }
//            }

//            var hConnect = InternetConnect(sessionHandle.Value, uri.Host, (ushort)uri.Port, null, null, InternetService.INTERNET_SERVICE_HTTP, 0, IntPtr.Zero);
//            if (hConnect == IntPtr.Zero)
//            {
//                throw new IOException($"InternetConnect failed: {Marshal.GetLastWin32Error()}");
//            }
//            var hRequest = HttpOpenRequest(hConnect, method, uri.PathAndQuery, null, null, null, (uri.Scheme == "https" ? INTERNET_FLAG.INTERNET_FLAG_SECURE | INTERNET_FLAG.INTERNET_FLAG_DONT_CACHE : INTERNET_FLAG.INTERNET_FLAG_DONT_CACHE), IntPtr.Zero);
//            //null, null, null,
//            //uri.Scheme == "https" ? WINHTTP_FLAG_ESCAPE_DISABLE | WINHTTP_FLAG_SECURE : WINHTTP_FLAG_ESCAPE_DISABLE);
//            if (hRequest == IntPtr.Zero)
//            {
//                throw new IOException($"HttpOpenRequest failed: {Marshal.GetLastWin32Error()}");
//            }

//            //var dwFlags = SECURITY_FLAG_IGNORE_UNKNOWN_CA |
//            //                    SECURITY_FLAG_IGNORE_CERT_WRONG_USAGE |
//            //                    SECURITY_FLAG_IGNORE_CERT_CN_INVALID |
//            //                    SECURITY_FLAG_IGNORE_CERT_DATE_INVALID;

//            //if (!WinHttpSetOption(
//            //    hRequest,
//            //    WINHTTP_OPTION_SECURITY_FLAGS,
//            //     ref dwFlags))
//            //{
//            //    Log.Debug("Ignore cert error: " + Marshal.GetLastWin32Error());
//            //}
//            //else
//            //{
//            //    Log.Debug("Ignore cert error config set");
//            //}

//            //dwFlags = WINHTTP_FLAG_SECURE_PROTOCOL_SSL3 | WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_1;

//            //if (!WinHttpSetOption(
//            //   hConnect,
//            //   WINHTTP_OPTION_SECURE_PROTOCOLS,
//            //    ref dwFlags))
//            //{
//            //    Log.Debug("WINHTTP_OPTION_SECURE_PROTOCOLS: " + Marshal.GetLastWin32Error());
//            //}
//            //else
//            //{
//            //    Log.Debug("WINHTTP_OPTION_SECURE_PROTOCOLS set");
//            //}

//            return new HttpRequest { Session = new WinInetSession(uri, hConnect, hRequest, headers, cookies) };
//        }

//        private void SetGlobalOption(InternetOptionFlags flag, int optionData)
//        {
//            var intPtr = Marshal.AllocHGlobal(4);
//            Marshal.StructureToPtr(optionData, intPtr, true);
//            if (!InternetSetOption(IntPtr.Zero, flag, intPtr, 4))
//            {
//                Log.Debug($"Unable to set {flag}: " + Marshal.GetLastWin32Error());
//            }
//            else
//            {
//                Log.Debug($"Global flag {flag} was set successfully");
//            }
//            Marshal.FreeHGlobal(intPtr);
//        }

//        public HttpRequest CreateGetRequest(
//            Uri uri,
//            Dictionary<string, List<string>>? headers = null,
//            Dictionary<string, string>? cookies = null,
//            AuthenticationInfo? authentication = null)
//        {
//            return CreateRequest(uri, "GET", headers, cookies, authentication);
//        }

//        public HttpRequest CreatePostRequest(
//            Uri uri,
//            Dictionary<string, List<string>>? headers = null,
//            Dictionary<string, string>? cookies = null,
//            AuthenticationInfo? authentication = null,
//            byte[]? body = null)
//        {
//            return CreateRequest(uri, "POST", headers, cookies, authentication, body);
//        }

//        public void Dispose()
//        {
//            disposed = true;
//            if (this.sessionHandle.HasValue)
//            {
//                InternetCloseHandle(this.sessionHandle.Value);
//            }
//        }

//        public void Close()
//        {
//            Dispose();
//        }

//        public HttpResponse Send(HttpRequest request)
//        {
//            var session = (WinInetSession)request.Session!;
//            var headerBuf = PrepareHeaders(session.Headers, session.Cookies, session.RangeStart, session.RangeEnd);
//            if (headerBuf.Length > 0)
//            {
//                Log.Debug("Setting header: " + headerBuf);
//                if (!HttpAddRequestHeaders(session.RequestHandle, headerBuf.ToString(), -1, HTTP_ADDREQ_FLAG.HTTP_ADDREQ_FLAG_REPLACE))
//                {
//                    throw new IOException($"HttpAddRequestHeaders failed: {Marshal.GetLastWin32Error()}\nHeaders:\n{headerBuf}");
//                }
//            }
//            if (HttpSendRequest(session.RequestHandle, null, 0, IntPtr.Zero, 0))
//            {
//                const int ERROR_INSUFFICIENT_BUFFER = 122;
//                uint lpdwindex = 0;
//                uint lpdwBufferLength = 0;
//                var error = 0;
//                if (!HttpQueryInfo(session.RequestHandle, HTTP_QUERY.HTTP_QUERY_RAW_HEADERS_CRLF,
//                    IntPtr.Zero, ref lpdwBufferLength, ref lpdwindex))
//                {
//                    error = Marshal.GetLastWin32Error();
//                    if (error == ERROR_INSUFFICIENT_BUFFER)
//                    {
//                        lpdwindex = 0;
//                        var hb = Marshal.AllocHGlobal((int)lpdwBufferLength);
//                        if (HttpQueryInfo(session.RequestHandle, HTTP_QUERY.HTTP_QUERY_RAW_HEADERS_CRLF,
//                            hb, ref lpdwBufferLength, ref lpdwindex))
//                        {
//                            var headers = Marshal.PtrToStringAuto(hb);//WinINet.HttpQueryInfo<string>(hRequest, WinINet.HTTP_QUERY.HTTP_QUERY_RAW_HEADERS_CRLF, ref lpdwindex);
//                            Console.WriteLine("header: " + headers);
//                            Marshal.FreeHGlobal(hb);
//                            session.SetHeaders(headers);
//                            return new HttpResponse { Session = session };
//                        }
//                        error = Marshal.GetLastWin32Error();
//                    }
//                }
//                throw new IOException($"HttpQueryInfo failed: {error}");
//            }
//            var text = $"HttpSendRequest failed: {Marshal.GetLastWin32Error()}";
//            Log.Debug(text);
//            throw new Exception(text);
//        }

//        //private uint CalculateHeaderBufferSize(SafeWinHttpHandle hRequest)
//        //{
//        //    uint bufferLengthInBytes = 0;
//        //    uint index = 0;
//        //    if (!WinHttpQueryHeaders(
//        //        hRequest,
//        //        WINHTTP_QUERY_RAW_HEADERS_CRLF,
//        //        WINHTTP_HEADER_NAME_BY_INDEX,
//        //        IntPtr.Zero,
//        //        ref bufferLengthInBytes,
//        //        ref index) && Marshal.GetLastWin32Error() == ERROR_INSUFFICIENT_BUFFER)
//        //    {
//        //        return bufferLengthInBytes;
//        //    }
//        //    return 0;
//        //}

//        //private string GetHeaders(SafeWinHttpHandle hRequest, uint bufferLengthInBytes)
//        //{
//        //    uint index = 0;
//        //    var pBuf = Marshal.AllocHGlobal((int)bufferLengthInBytes);
//        //    if (!WinHttpQueryHeaders(
//        //        hRequest,
//        //        WINHTTP_QUERY_RAW_HEADERS_CRLF,
//        //        WINHTTP_HEADER_NAME_BY_INDEX,
//        //        pBuf,
//        //        ref bufferLengthInBytes,
//        //        ref index))
//        //    {
//        //        throw new IOException("Unable to read headers: " + Marshal.GetLastWin32Error());
//        //    }
//        //    Log.Debug("Header len after read: " + bufferLengthInBytes);
//        //    var header = Marshal.PtrToStringAuto(pBuf);
//        //    Marshal.FreeHGlobal(pBuf);
//        //    return header;
//        //}

//        private StringBuilder PrepareHeaders(Dictionary<string, List<string>>? headers = null,
//            Dictionary<string, string>? cookies = null,
//            long rangeStart = -1, long rangeEnd = -1)
//        {
//            var buf = new StringBuilder();
//            if (headers != null)
//            {
//                foreach (var key in headers.Keys)
//                {
//                    var values = headers[key];
//                    if (values != null && values.Count > 0)
//                    {
//                        var text = string.Join(", ", headers[key].Where(x => !string.IsNullOrEmpty(x)).ToArray());
//                        if (!string.IsNullOrEmpty(text))
//                        {
//                            buf.Append(key).Append(": ").Append(text).Append("\r\n");
//                        }
//                    }
//                }
//            }
//            if (cookies != null && cookies.Count > 0)
//            {
//                buf.Append("Cookie: ");
//                var first = true;
//                foreach (var key in cookies.Keys)
//                {
//                    if (!first) buf.Append(", ");
//                    buf.Append(cookies[key]);
//                    first = false;
//                }
//                buf.Append("\r\n");
//            }
//            if (rangeStart > 0 && rangeEnd > 0)
//            {
//                buf.Append(string.Format("Range: bytes={0}-{1}", rangeStart, rangeEnd));
//            }
//            else if (rangeStart > 0)
//            {
//                buf.Append(string.Format("Range: bytes={0}-", rangeStart));
//            }
//            return buf;
//        }

//        //private bool GetProxyForUrl(Uri url, out string? proxyHost, out string? bypass)
//        //{
//        //    proxyHost = null;
//        //    bypass = null;
//        //    var handle = WinHttpOpen(
//        //                    IntPtr.Zero,
//        //                    WINHTTP_ACCESS_TYPE_NO_PROXY,
//        //                    WINHTTP_NO_PROXY_NAME,
//        //                    WINHTTP_NO_PROXY_BYPASS,
//        //                    0);
//        //    try
//        //    {
//        //        var _proxyHelper = new WinInetProxyHelper();
//        //        if (_proxyHelper.GetProxyForUrl(handle, url, out WINHTTP_PROXY_INFO info))
//        //        {
//        //            proxyHost = Marshal.PtrToStringAuto(info.Proxy);
//        //            bypass = Marshal.PtrToStringAuto(info.ProxyBypass);
//        //            return true;
//        //        }
//        //        return false;
//        //    }
//        //    finally
//        //    {
//        //        handle.Close();
//        //    }
//        //}

//        [DllImport("wininet.dll", CharSet = CharSet.Unicode, SetLastError = true)]
//        public static extern IntPtr InternetOpen(string lpszAgent, InternetOpenType dwAccessType, string lpszProxy, string lpszProxyBypass, InternetApiFlags dwFlags);

//        [DllImport("wininet.dll", CharSet = CharSet.Unicode, SetLastError = true)]
//        public static extern bool InternetSetOption([In] IntPtr hInternet, [In] InternetOptionFlags dwOption, [In] IntPtr lpBuffer, [In] UInt32 lpdwBufferLength);

//        [DllImport("wininet.dll", CharSet = CharSet.Unicode, SetLastError = true)]
//        public extern static IntPtr InternetConnect(IntPtr hInternet, string lpszServerName, ushort nServerPort, string lpszUserName, string lpszPassword, InternetService dwService, InternetApiFlags dwFlags, IntPtr dwContext);

//        [DllImport("wininet.dll", CharSet = CharSet.Unicode, SetLastError = true)]
//        public extern static IntPtr HttpOpenRequest(IntPtr hConnect, string lpszVerb, string lpszObjectName, string lpszVersion, string lpszReferrer, string[] lplpszAcceptTypes, INTERNET_FLAG dwFlags, IntPtr dwContext);

//        [DllImport("wininet.dll", CharSet = CharSet.Unicode, SetLastError = true)]
//        public extern static bool InternetCloseHandle(IntPtr hInternet);

//        [DllImport("wininet.dll", CharSet = CharSet.Unicode, SetLastError = true)]
//        public extern static bool HttpAddRequestHeaders(IntPtr hRequest, [MarshalAs(UnmanagedType.LPTStr)] string lpszHeaders, int dwHeadersLength, HTTP_ADDREQ_FLAG dwModifiers);

//        [DllImport("wininet.dll", CharSet = CharSet.Unicode, SetLastError = true)]
//        public extern static bool HttpSendRequest(IntPtr hRequest, string lpszHeaders, uint dwHeadersLength, IntPtr lpOptional, uint dwOptionalLength);

//        [DllImport("wininet.dll", CharSet = CharSet.Unicode, SetLastError = true)]
//        public extern static bool HttpQueryInfo(IntPtr hRequest, HTTP_QUERY dwInfoLevel, IntPtr lpBuffer, ref uint lpdwBufferLength, ref uint lpdwIndex);



















//    }

//    public enum InternetOpenType
//    {
//        //
//        // Summary:
//        //     Retrieves the proxy or direct configuration from the registry.
//        INTERNET_OPEN_TYPE_PRECONFIG = 0,
//        //
//        // Summary:
//        //     Resolves all host names locally.
//        INTERNET_OPEN_TYPE_DIRECT = 1,
//        //
//        // Summary:
//        //     Passes requests to the proxy unless a proxy bypass list is supplied and the name
//        //     to be resolved bypasses the proxy. In this case, the function uses INTERNET_OPEN_TYPE_DIRECT.
//        INTERNET_OPEN_TYPE_PROXY = 3,
//        //
//        // Summary:
//        //     Retrieves the proxy or direct configuration from the registry and prevents the
//        //     use of a startup Microsoft JScript or Internet Setup (INS) file.
//        INTERNET_OPEN_TYPE_PRECONFIG_WITH_NO_AUTOPROXY = 4
//    }

//    public enum INTERNET_FLAG : uint
//    {
//        INTERNET_FLAG_TRANSFER_ASCII = 1,
//        INTERNET_FLAG_TRANSFER_BINARY = 2,
//        //
//        // Summary:
//        //     need a file for this request
//        INTERNET_FLAG_NEED_FILE = 16,
//        //
//        // Summary:
//        //     need a file for this request
//        INTERNET_FLAG_MUST_CACHE_REQUEST = 16,
//        //
//        // Summary:
//        //     fwd-back button op
//        INTERNET_FLAG_FWD_BACK = 32,
//        //
//        // Summary:
//        //     this is a forms submit
//        INTERNET_FLAG_FORMS_SUBMIT = 64,
//        //
//        // Summary:
//        //     ok to perform lazy cache-write
//        INTERNET_FLAG_CACHE_ASYNC = 128,
//        //
//        // Summary:
//        //     asking wininet to add "pragma: no-cache"
//        INTERNET_FLAG_PRAGMA_NOCACHE = 256,
//        //
//        // Summary:
//        //     no cookie popup
//        INTERNET_FLAG_NO_UI = 512,
//        //
//        // Summary:
//        //     asking wininet to do hyperlinking semantic which works right for scripts
//        INTERNET_FLAG_HYPERLINK = 1024,
//        //
//        // Summary:
//        //     asking wininet to update an item if it is newer
//        INTERNET_FLAG_RESYNCHRONIZE = 2048,
//        //
//        // Summary:
//        //     bad common name in X509 Cert.
//        INTERNET_FLAG_IGNORE_CERT_CN_INVALID = 4096,
//        //
//        // Summary:
//        //     expired X509 Cert.
//        INTERNET_FLAG_IGNORE_CERT_DATE_INVALID = 8192,
//        //
//        // Summary:
//        //     ex: http:// to https://
//        INTERNET_FLAG_IGNORE_REDIRECT_TO_HTTPS = 16384,
//        //
//        // Summary:
//        //     ex: https:// to http://
//        INTERNET_FLAG_IGNORE_REDIRECT_TO_HTTP = 32768,
//        //
//        // Summary:
//        //     return cache file if net request fails
//        INTERNET_FLAG_CACHE_IF_NET_FAIL = 65536,
//        //
//        // Summary:
//        //     no automatic authentication handling
//        INTERNET_FLAG_NO_AUTH = 262144,
//        //
//        // Summary:
//        //     no automatic cookie handling
//        INTERNET_FLAG_NO_COOKIES = 524288,
//        //
//        // Summary:
//        //     do background read prefetch
//        INTERNET_FLAG_READ_PREFETCH = 1048576,
//        //
//        // Summary:
//        //     don't handle redirections automatically
//        INTERNET_FLAG_NO_AUTO_REDIRECT = 2097152,
//        //
//        // Summary:
//        //     use keep-alive semantics
//        INTERNET_FLAG_KEEP_CONNECTION = 4194304,
//        //
//        // Summary:
//        //     use PCT/SSL if applicable (HTTP)
//        INTERNET_FLAG_SECURE = 8388608,
//        //
//        // Summary:
//        //     use offline semantics
//        INTERNET_FLAG_FROM_CACHE = 16777216,
//        //
//        // Summary:
//        //     use offline semantics
//        INTERNET_FLAG_OFFLINE = 16777216,
//        //
//        // Summary:
//        //     make this item persistent in cache
//        INTERNET_FLAG_MAKE_PERSISTENT = 33554432,
//        //
//        // Summary:
//        //     don't write this item to the cache
//        INTERNET_FLAG_NO_CACHE_WRITE = 67108864,
//        //
//        // Summary:
//        //     don't write this item to the cache
//        INTERNET_FLAG_DONT_CACHE = 67108864,
//        //
//        // Summary:
//        //     used for FTP connections
//        INTERNET_FLAG_PASSIVE = 134217728,
//        //
//        // Summary:
//        //     this request is asynchronous (where supported)
//        INTERNET_FLAG_ASYNC = 268435456,
//        //
//        // Summary:
//        //     FTP: use existing InternetConnect handle for server if possible
//        INTERNET_FLAG_EXISTING_CONNECT = 536870912,
//        //
//        // Summary:
//        //     FTP/gopher find: receive the item as raw (structured) data
//        INTERNET_FLAG_RAW_DATA = 1073741824,
//        //
//        // Summary:
//        //     retrieve the original item
//        INTERNET_FLAG_RELOAD = 2147483648
//    }

//    public enum HTTP_ADDREQ_FLAG : uint
//    {
//        //
//        // Summary:
//        //     Coalesces headers of the same name using a semicolon.
//        HTTP_ADDREQ_FLAG_COALESCE_WITH_SEMICOLON = 16777216,
//        //
//        // Summary:
//        //     Adds the header only if it does not already exist; otherwise, an error is returned.
//        HTTP_ADDREQ_FLAG_ADD_IF_NEW = 268435456,
//        //
//        // Summary:
//        //     Adds the header if it does not exist. Used with HTTP_ADDREQ_FLAG_REPLACE.
//        HTTP_ADDREQ_FLAG_ADD = 536870912,
//        //
//        // Summary:
//        //     Coalesces headers of the same name. For example, adding "Accept: text/*" followed
//        //     by "Accept: audio/*" with this flag results in the formation of the single header
//        //     "Accept: text/*, audio/*". This causes the first header found to be coalesced.
//        //     It is up to the calling application to ensure a cohesive scheme with respect
//        //     to coalesced/separate headers.
//        HTTP_ADDREQ_FLAG_COALESCE_WITH_COMMA = 1073741824,
//        //
//        // Summary:
//        //     Coalesces headers of the same name.
//        HTTP_ADDREQ_FLAG_COALESCE = 1073741824,
//        //
//        // Summary:
//        //     Replaces or removes a header. If the header value is empty and the header is
//        //     found, it is removed. If not empty, the header value is replaced.
//        HTTP_ADDREQ_FLAG_REPLACE = 2147483648
//    }

//    public enum HTTP_QUERY : uint
//    {
//        //
//        // Summary:
//        //     Receives the version of the MIME protocol that was used to construct the message.
//        HTTP_QUERY_MIME_VERSION = 0,
//        //
//        // Summary:
//        //     Receives the content type of the resource (such as text/html).
//        HTTP_QUERY_CONTENT_TYPE = 1,
//        //
//        // Summary:
//        //     Receives the additional content coding that has been applied to the resource.
//        HTTP_QUERY_CONTENT_TRANSFER_ENCODING = 2,
//        //
//        // Summary:
//        //     Retrieves the content identification.
//        HTTP_QUERY_CONTENT_ID = 3,
//        //
//        // Summary:
//        //     Obsolete. Maintained for legacy application compatibility only.
//        HTTP_QUERY_CONTENT_DESCRIPTION = 4,
//        //
//        // Summary:
//        //     Retrieves the size of the resource, in bytes.
//        HTTP_QUERY_CONTENT_LENGTH = 5,
//        //
//        // Summary:
//        //     Retrieves the language that the content is in.
//        HTTP_QUERY_CONTENT_LANGUAGE = 6,
//        //
//        // Summary:
//        //     Receives the HTTP verbs supported by the server.
//        HTTP_QUERY_ALLOW = 7,
//        //
//        // Summary:
//        //     Receives methods available at this server.
//        HTTP_QUERY_PUBLIC = 8,
//        //
//        // Summary:
//        //     Receives the date and time at which the message was originated.
//        HTTP_QUERY_DATE = 9,
//        //
//        // Summary:
//        //     Receives the date and time after which the resource should be considered outdated.
//        HTTP_QUERY_EXPIRES = 10,
//        //
//        // Summary:
//        //     Receives the date and time at which the server believes the resource was last
//        //     modified.
//        HTTP_QUERY_LAST_MODIFIED = 11,
//        //
//        // Summary:
//        //     No longer supported.
//        HTTP_QUERY_MESSAGE_ID = 12,
//        //
//        // Summary:
//        //     Receives some or all of the Uniform Resource Identifiers (URIs) by which the
//        //     Request-URI resource can be identified.
//        HTTP_QUERY_URI = 13,
//        //
//        // Summary:
//        //     No longer supported.
//        HTTP_QUERY_DERIVED_FROM = 14,
//        //
//        // Summary:
//        //     No longer supported.
//        HTTP_QUERY_COST = 15,
//        //
//        // Summary:
//        //     Obsolete. Maintained for legacy application compatibility only.
//        HTTP_QUERY_LINK = 16,
//        //
//        // Summary:
//        //     Receives the implementation-specific directives that might apply to any recipient
//        //     along the request/response chain.
//        HTTP_QUERY_PRAGMA = 17,
//        //
//        // Summary:
//        //     Receives the last response code returned by the server.
//        HTTP_QUERY_VERSION = 18,
//        //
//        // Summary:
//        //     Receives the status code returned by the server. For more information and a list
//        //     of possible values, see HTTP Status Codes.
//        HTTP_QUERY_STATUS_CODE = 19,
//        //
//        // Summary:
//        //     Receives any additional text returned by the server on the response line.
//        HTTP_QUERY_STATUS_TEXT = 20,
//        //
//        // Summary:
//        //     Receives all the headers returned by the server. Each header is terminated by
//        //     "\0". An additional "\0" terminates the list of headers.
//        HTTP_QUERY_RAW_HEADERS = 21,
//        //
//        // Summary:
//        //     Receives all the headers returned by the server. Each header is separated by
//        //     a carriage return/line feed (CR/LF) sequence.
//        HTTP_QUERY_RAW_HEADERS_CRLF = 22,
//        //
//        // Summary:
//        //     Retrieves any options that are specified for a particular connection and must
//        //     not be communicated by proxies over further connections.
//        HTTP_QUERY_CONNECTION = 23,
//        //
//        // Summary:
//        //     Retrieves the acceptable media types for the response.
//        HTTP_QUERY_ACCEPT = 24,
//        //
//        // Summary:
//        //     Retrieves the acceptable character sets for the response.
//        HTTP_QUERY_ACCEPT_CHARSET = 25,
//        //
//        // Summary:
//        //     Retrieves the acceptable content-coding values for the response.
//        HTTP_QUERY_ACCEPT_ENCODING = 26,
//        //
//        // Summary:
//        //     Retrieves the acceptable natural languages for the response.
//        HTTP_QUERY_ACCEPT_LANGUAGE = 27,
//        //
//        // Summary:
//        //     Retrieves the authorization credentials used for a request.
//        HTTP_QUERY_AUTHORIZATION = 28,
//        //
//        // Summary:
//        //     Retrieves any additional content codings that have been applied to the entire
//        //     resource.
//        HTTP_QUERY_CONTENT_ENCODING = 29,
//        //
//        // Summary:
//        //     Obsolete. Maintained for legacy application compatibility only.
//        HTTP_QUERY_FORWARDED = 30,
//        //
//        // Summary:
//        //     Retrieves the email address for the human user who controls the requesting user
//        //     agent if the From header is given.
//        HTTP_QUERY_FROM = 31,
//        //
//        // Summary:
//        //     Retrieves the contents of the If-Modified-Since header.
//        HTTP_QUERY_IF_MODIFIED_SINCE = 32,
//        //
//        // Summary:
//        //     Retrieves the absolute Uniform Resource Identifier (URI) used in a Location response-header.
//        HTTP_QUERY_LOCATION = 33,
//        //
//        // Summary:
//        //     Obsolete. Maintained for legacy application compatibility only.
//        HTTP_QUERY_ORIG_URI = 34,
//        //
//        // Summary:
//        //     Receives the Uniform Resource Identifier (URI) of the resource where the requested
//        //     URI was obtained.
//        HTTP_QUERY_REFERER = 35,
//        //
//        // Summary:
//        //     Retrieves the amount of time the service is expected to be unavailable.
//        HTTP_QUERY_RETRY_AFTER = 36,
//        //
//        // Summary:
//        //     Retrieves data about the software used by the origin server to handle the request.
//        HTTP_QUERY_SERVER = 37,
//        //
//        // Summary:
//        //     Obsolete. Maintained for legacy application compatibility only.
//        HTTP_QUERY_TITLE = 38,
//        //
//        // Summary:
//        //     Retrieves data about the user agent that made the request.
//        HTTP_QUERY_USER_AGENT = 39,
//        //
//        // Summary:
//        //     Retrieves the authentication scheme and realm returned by the server.
//        HTTP_QUERY_WWW_AUTHENTICATE = 40,
//        //
//        // Summary:
//        //     Retrieves the authentication scheme and realm returned by the proxy.
//        HTTP_QUERY_PROXY_AUTHENTICATE = 41,
//        //
//        // Summary:
//        //     Retrieves the types of range requests that are accepted for a resource.
//        HTTP_QUERY_ACCEPT_RANGES = 42,
//        //
//        // Summary:
//        //     Receives the value of the cookie set for the request.
//        HTTP_QUERY_SET_COOKIE = 43,
//        //
//        // Summary:
//        //     Retrieves any cookies associated with the request.
//        HTTP_QUERY_COOKIE = 44,
//        //
//        // Summary:
//        //     Receives the HTTP verb that is being used in the request, typically GET or POST.
//        HTTP_QUERY_REQUEST_METHOD = 45,
//        //
//        // Summary:
//        //     Obsolete. Maintained for legacy application compatibility only.
//        HTTP_QUERY_REFRESH = 46,
//        //
//        // Summary:
//        //     Obsolete. Maintained for legacy application compatibility only.
//        HTTP_QUERY_CONTENT_DISPOSITION = 47,
//        //
//        // Summary:
//        //     Retrieves the Age response-header field, which contains the sender's estimate
//        //     of the amount of time since the response was generated at the origin server.
//        HTTP_QUERY_AGE = 48,
//        //
//        // Summary:
//        //     Retrieves the cache control directives.
//        HTTP_QUERY_CACHE_CONTROL = 49,
//        //
//        // Summary:
//        //     Retrieves the base URI (Uniform Resource Identifier) for resolving relative URLs
//        //     within the entity.
//        HTTP_QUERY_CONTENT_BASE = 50,
//        //
//        // Summary:
//        //     Retrieves the resource location for the entity enclosed in the message.
//        HTTP_QUERY_CONTENT_LOCATION = 51,
//        //
//        // Summary:
//        //     Retrieves an MD5 digest of the entity-body for the purpose of providing an end-to-end
//        //     message integrity check (MIC) for the entity-body. For more information, see
//        //     RFC1864, The Content-MD5 Header Field, at https://ftp.isi.edu/in-notes/rfc1864.txt.
//        HTTP_QUERY_CONTENT_MD5 = 52,
//        //
//        // Summary:
//        //     Retrieves the location in the full entity-body where the partial entity-body
//        //     should be inserted and the total size of the full entity-body.
//        HTTP_QUERY_CONTENT_RANGE = 53,
//        //
//        // Summary:
//        //     Retrieves the entity tag for the associated entity.
//        HTTP_QUERY_ETAG = 54,
//        //
//        // Summary:
//        //     Retrieves the Internet host and port number of the resource being requested.
//        HTTP_QUERY_HOST = 55,
//        //
//        // Summary:
//        //     Retrieves the contents of the If-Match request-header field.
//        HTTP_QUERY_IF_MATCH = 56,
//        //
//        // Summary:
//        //     Retrieves the contents of the If-None-Match request-header field.
//        HTTP_QUERY_IF_NONE_MATCH = 57,
//        //
//        // Summary:
//        //     Retrieves the contents of the If-Range request-header field. This header enables
//        //     the client application to verify that the entity related to a partial copy of
//        //     the entity in the client application cache has not been updated. If the entity
//        //     has not been updated, send the parts that the client application is missing.
//        //     If the entity has been updated, send the entire updated entity.
//        HTTP_QUERY_IF_RANGE = 58,
//        //
//        // Summary:
//        //     Retrieves the contents of the If-Unmodified-Since request-header field.
//        HTTP_QUERY_IF_UNMODIFIED_SINCE = 59,
//        //
//        // Summary:
//        //     Retrieves the number of proxies or gateways that can forward the request to the
//        //     next inbound server.
//        HTTP_QUERY_MAX_FORWARDS = 60,
//        //
//        // Summary:
//        //     Retrieves the header that is used to identify the user to a proxy that requires
//        //     authentication. This header can only be retrieved before the request is sent
//        //     to the server.
//        HTTP_QUERY_PROXY_AUTHORIZATION = 61,
//        //
//        // Summary:
//        //     Retrieves the byte range of an entity.
//        HTTP_QUERY_RANGE = 62,
//        //
//        // Summary:
//        //     Retrieves the type of transformation that has been applied to the message body
//        //     so it can be safely transferred between the sender and recipient.
//        HTTP_QUERY_TRANSFER_ENCODING = 63,
//        //
//        // Summary:
//        //     Retrieves the additional communication protocols that are supported by the server.
//        HTTP_QUERY_UPGRADE = 64,
//        //
//        // Summary:
//        //     Retrieves the header that indicates that the entity was selected from a number
//        //     of available representations of the response using server-driven negotiation.
//        HTTP_QUERY_VARY = 65,
//        //
//        // Summary:
//        //     Retrieves the intermediate protocols and recipients between the user agent and
//        //     the server on requests, and between the origin server and the client on responses.
//        HTTP_QUERY_VIA = 66,
//        //
//        // Summary:
//        //     Retrieves additional data about the status of a response that might not be reflected
//        //     by the response status code.
//        HTTP_QUERY_WARNING = 67,
//        //
//        // Summary:
//        //     Retrieves the Expect header, which indicates whether the client application should
//        //     expect 100 series responses.
//        HTTP_QUERY_EXPECT = 68,
//        //
//        // Summary:
//        //     Retrieves the Proxy-Connection header.
//        HTTP_QUERY_PROXY_CONNECTION = 69,
//        //
//        // Summary:
//        //     Retrieves the Unless-Modified-Since header.
//        HTTP_QUERY_UNLESS_MODIFIED_SINCE = 70,
//        //
//        // Summary:
//        //     Not currently implemented.
//        HTTP_QUERY_ECHO_REQUEST = 71,
//        //
//        // Summary:
//        //     Not currently implemented.
//        HTTP_QUERY_ECHO_REPLY = 72,
//        //
//        // Summary:
//        //     Not currently implemented.
//        HTTP_QUERY_ECHO_HEADERS = 73,
//        //
//        // Summary:
//        //     Not currently implemented.
//        HTTP_QUERY_ECHO_HEADERS_CRLF = 74,
//        //
//        // Summary:
//        //     Not a query flag. Indicates the maximum value of an HTTP_QUERY_* value.
//        HTTP_QUERY_MAX = 78,
//        //
//        // Summary:
//        //     Retrieves the X-Content-Type-Options header value.
//        HTTP_QUERY_X_CONTENT_TYPE_OPTIONS = 79,
//        //
//        // Summary:
//        //     Retrieves the P3P header value.
//        HTTP_QUERY_P3P = 80,
//        //
//        // Summary:
//        //     Retrieves the X-P2P-PeerDist header value.
//        HTTP_QUERY_X_P2P_PEERDIST = 81,
//        //
//        // Summary:
//        //     Retrieves the translate header value.
//        HTTP_QUERY_TRANSLATE = 82,
//        //
//        // Summary:
//        //     Retrieves the X-UA-Compatible header value.
//        HTTP_QUERY_X_UA_COMPATIBLE = 83,
//        //
//        // Summary:
//        //     Retrieves the Default-Style header value.
//        HTTP_QUERY_DEFAULT_STYLE = 84,
//        //
//        // Summary:
//        //     Retrieves the X-Frame-Options header value.
//        HTTP_QUERY_X_FRAME_OPTIONS = 85,
//        //
//        // Summary:
//        //     Retrieves the X-XSS-Protection header value.
//        //     The modifier flags are used in conjunction with an attribute flag to modify the
//        //     request. Modifier flags either modify the format of the data returned or indicate
//        //     where HttpQueryInfo (or QueryInfo) should search for the data.
//        HTTP_QUERY_X_XSS_PROTECTION = 86,
//        //
//        // Summary:
//        //     Causes HttpQueryInfo to search for the header name specified in lpvBuffer and
//        //     store the header data in lpvBuffer.
//        HTTP_QUERY_CUSTOM = 65535,
//        //
//        // Summary:
//        //     Not implemented.
//        HTTP_QUERY_FLAG_COALESCE = 268435456,
//        //
//        // Summary:
//        //     Returns the data as a 32-bit number for headers whose value is a number, such
//        //     as the status code.
//        HTTP_QUERY_FLAG_NUMBER = 536870912,
//        //
//        // Summary:
//        //     Returns the header value as a SYSTEMTIME structure, which does not require the
//        //     application to parse the data. Use for headers whose value is a date/time string,
//        //     such as "Last-Modified-Time".
//        HTTP_QUERY_FLAG_SYSTEMTIME = 1073741824,
//        //
//        // Summary:
//        //     Queries request headers only.
//        HTTP_QUERY_FLAG_REQUEST_HEADERS = 2147483648
//    }

//    public enum InternetService
//    {
//        //
//        // Summary:
//        //     FTP service.
//        INTERNET_SERVICE_FTP = 1,
//        //
//        // Summary:
//        //     Gopher service. Windows XP and Windows Server 2003 R2 and earlier only.
//        INTERNET_SERVICE_GOPHER = 2,
//        //
//        // Summary:
//        //     HTTP service.
//        INTERNET_SERVICE_HTTP = 3
//    }

//    public enum InternetApiFlags : uint
//    {
//        //
//        // Summary:
//        //     Indicates that no callbacks should be made for that API. This is used for the
//        //     dxContext parameter of the functions that allow asynchronous operations.
//        INTERNET_NO_CALLBACK = 0,
//        //
//        // Summary:
//        //     Transfers file as ASCII (FTP only). This flag can be used by FtpOpenFile, FtpGetFile,
//        //     and FtpPutFile.
//        INTERNET_FLAG_TRANSFER_ASCII = 1,
//        //
//        // Summary:
//        //     Forces asynchronous operations.
//        WININET_API_FLAG_ASYNC = 1,
//        //
//        // Summary:
//        //     Transfers file as binary (FTP only). This flag can be used by FtpOpenFile, FtpGetFile,
//        //     and FtpPutFile.
//        INTERNET_FLAG_TRANSFER_BINARY = 2,
//        //
//        // Summary:
//        //     Forces synchronous operations.
//        WININET_API_FLAG_SYNC = 4,
//        //
//        // Summary:
//        //     Forces the API to use the context value, even if it is set to zero.
//        WININET_API_FLAG_USE_CONTEXT = 8,
//        //
//        // Summary:
//        //     Indicates that a third-party cookie is being set or retrieved.
//        INTERNET_COOKIE_THIRD_PARTY = 16,
//        //
//        // Summary:
//        //     Identical to the preferred value, INTERNET_FLAG_NEED_FILE. Causes a temporary
//        //     file to be created if the file cannot be cached. This flag can be used by FtpFindFirstFile,
//        //     FtpGetFile, FtpOpenFile, FtpPutFile, HttpOpenRequest, and InternetOpenUrl.
//        //     Windows XP and Windows Server 2003 R2 and earlier: Also used by GopherFindFirstFile
//        //     and GopherOpenFile.
//        INTERNET_FLAG_MUST_CACHE_REQUEST = 16,
//        //
//        // Summary:
//        //     Causes a temporary file to be created if the file cannot be cached. This flag
//        //     can be used by FtpFindFirstFile, FtpGetFile, FtpOpenFile, FtpPutFile, HttpOpenRequest,
//        //     and InternetOpenUrl.
//        //     Windows XP and Windows Server 2003 R2 and earlier: Also used by GopherFindFirstFile
//        //     and GopherOpenFile.
//        INTERNET_FLAG_NEED_FILE = 16,
//        //
//        // Summary:
//        //     Indicates that the function should use the copy of the resource that is currently
//        //     in the Internet cache. The expiration date and other information about the resource
//        //     is not checked. If the requested item is not found in the Internet cache, the
//        //     system attempts to locate the resource on the network. This value was introduced
//        //     in Microsoft Internet Explorer 5 and is associated with the Forward and Back
//        //     button operations of Internet Explorer.
//        INTERNET_FLAG_FWD_BACK = 32,
//        //
//        // Summary:
//        //     Indicates that this is a Forms submission.
//        INTERNET_FLAG_FORMS_SUBMIT = 64,
//        //
//        // Summary:
//        //     Sets an HTTP request object such that it will not logon to origin servers, but
//        //     will perform automatic logon to HTTP proxy servers. This option differs from
//        //     the Request flag INTERNET_FLAG_NO_AUTH, which prevents authentication to both
//        //     proxy servers and origin servers. Setting this mode will suppress the use of
//        //     any credential material (either previously provided username/password or client
//        //     SSL certificate) when communicating with an origin server. However, if the request
//        //     must transit via an authenticating proxy, WinINet will still perform automatic
//        //     authentication to the HTTP proxy per the Intranet Zone settings for the user.
//        //     The default Intranet Zone setting is to permit automatic logon using the user’s
//        //     default credentials. To ensure suppression of all identifying information, the
//        //     caller should combine INTERNET_OPTION_SUPPRESS_SERVER_AUTH with the INTERNET_FLAG_NO_COOKIES
//        //     request flag. This option may only be set on request objects before they have
//        //     been sent. Attempts to set this option after the request has been sent will return
//        //     ERROR_INTERNET_INCORRECT_HANDLE_STATE. No buffer is required for this option.
//        //     This is used by InternetSetOption on handles returned by HttpOpenRequest only.
//        //     Version: Requires Internet Explorer 8.0 or later.
//        INTERNET_OPTION_SUPPRESS_SERVER_AUTH = 104,
//        //
//        // Summary:
//        //     Indicates that a Platform for Privacy Protection (P3P) header is to be associated
//        //     with a cookie.
//        INTERNET_COOKIE_EVALUATE_P3P = 128,
//        //
//        // Summary:
//        //     Allows a lazy cache write.
//        INTERNET_FLAG_CACHE_ASYNC = 128,
//        //
//        // Summary:
//        //     Forces the request to be resolved by the origin server, even if a cached copy
//        //     exists on the proxy. The InternetOpenUrl function (on HTTP and HTTPS requests
//        //     only) and HttpOpenRequest function use this flag.
//        INTERNET_FLAG_PRAGMA_NOCACHE = 256,
//        //
//        // Summary:
//        //     Disables the cookie dialog box. This flag can be used by HttpOpenRequest and
//        //     InternetOpenUrl (HTTP requests only).
//        INTERNET_FLAG_NO_UI = 512,
//        //
//        // Summary:
//        //     Forces a reload if there is no Expires time and no LastModified time returned
//        //     from the server when determining whether to reload the item from the network.
//        //     This flag can be used by FtpFindFirstFile, FtpGetFile, FtpOpenFile, FtpPutFile,
//        //     HttpOpenRequest, and InternetOpenUrl.
//        //     Windows XP and Windows Server 2003 R2 and earlier: Also used by GopherFindFirstFile
//        //     and GopherOpenFile.
//        INTERNET_FLAG_HYPERLINK = 1024,
//        //
//        // Summary:
//        //     Reloads HTTP resources if the resource has been modified since the last time
//        //     it was downloaded. All FTP resources are reloaded. This flag can be used by FtpFindFirstFile,
//        //     FtpGetFile, FtpOpenFile, FtpPutFile, HttpOpenRequest, and InternetOpenUrl.
//        //     Windows XP and Windows Server 2003 R2 and earlier: Also used by GopherFindFirstFile
//        //     and GopherOpenFile, and Gopher resources are reloaded.
//        INTERNET_FLAG_RESYNCHRONIZE = 2048,
//        //
//        // Summary:
//        //     Disables checking of SSL/PCT-based certificates that are returned from the server
//        //     against the host name given in the request. WinINet uses a simple check against
//        //     certificates by comparing for matching host names and simple wildcarding rules.
//        //     This flag can be used by HttpOpenRequest and InternetOpenUrl (for HTTP requests).
//        INTERNET_FLAG_IGNORE_CERT_CN_INVALID = 4096,
//        //
//        // Summary:
//        //     Disables checking of SSL/PCT-based certificates for proper validity dates. This
//        //     flag can be used by HttpOpenRequest and InternetOpenUrl (for HTTP requests).
//        INTERNET_FLAG_IGNORE_CERT_DATE_INVALID = 8192,
//        //
//        // Summary:
//        //     Disables detection of this special type of redirect. When this flag is used,
//        //     WinINet transparently allow redirects from HTTP to HTTPS URLs. This flag can
//        //     be used by HttpOpenRequest and InternetOpenUrl (for HTTP requests).
//        INTERNET_FLAG_IGNORE_REDIRECT_TO_HTTPS = 16384,
//        //
//        // Summary:
//        //     Disables detection of this special type of redirect. When this flag is used,
//        //     WinINet transparently allows redirects from HTTPS to HTTP URLs. This flag can
//        //     be used by HttpOpenRequest and InternetOpenUrl (for HTTP requests).
//        INTERNET_FLAG_IGNORE_REDIRECT_TO_HTTP = 32768,
//        //
//        // Summary:
//        //     Returns the resource from the cache if the network request for the resource fails
//        //     due to an ERROR_INTERNET_CONNECTION_RESET or ERROR_INTERNET_CANNOT_CONNECT error.
//        //     This flag is used by HttpOpenRequest.
//        INTERNET_FLAG_CACHE_IF_NET_FAIL = 65536,
//        //
//        // Summary:
//        //     Indicates that the cookie being set is associated with an untrusted site.
//        INTERNET_FLAG_RESTRICTED_ZONE = 131072,
//        //
//        // Summary:
//        //     Does not attempt authentication automatically. This flag can be used by HttpOpenRequest
//        //     and InternetOpenUrl (for HTTP requests).
//        INTERNET_FLAG_NO_AUTH = 262144,
//        //
//        // Summary:
//        //     Does not automatically add cookie headers to requests, and does not automatically
//        //     add returned cookies to the cookie database. This flag can be used by HttpOpenRequest
//        //     and InternetOpenUrl (for HTTP requests).
//        INTERNET_FLAG_NO_COOKIES = 524288,
//        //
//        // Summary:
//        //     This flag is currently disabled.
//        INTERNET_FLAG_READ_PREFETCH = 1048576,
//        //
//        // Summary:
//        //     Does not automatically handle redirection in HttpSendRequest. This flag can also
//        //     be used by InternetOpenUrl for HTTP requests.
//        INTERNET_FLAG_NO_AUTO_REDIRECT = 2097152,
//        //
//        // Summary:
//        //     Uses keep-alive semantics, if available, for the connection. This flag is used
//        //     by HttpOpenRequest and InternetOpenUrl (for HTTP requests). This flag is required
//        //     for Microsoft Network (MSN), NTLM, and other types of authentication.
//        INTERNET_FLAG_KEEP_CONNECTION = 4194304,
//        //
//        // Summary:
//        //     Uses secure transaction semantics. This translates to using Secure Sockets Layer/Private
//        //     Communications Technology (SSL/PCT) and is only meaningful in HTTP requests.
//        //     This flag is used by HttpOpenRequest and InternetOpenUrl, but this is redundant
//        //     if https:// appears in the URL.The InternetConnect function uses this flag for
//        //     HTTP connections; all the request handles created under this connection will
//        //     inherit this flag.
//        INTERNET_FLAG_SECURE = 8388608,
//        //
//        // Summary:
//        //     Does not make network requests. All entities are returned from the cache. If
//        //     the requested item is not in the cache, a suitable error, such as ERROR_FILE_NOT_FOUND,
//        //     is returned. Only the InternetOpen function uses this flag.
//        INTERNET_FLAG_FROM_CACHE = 16777216,
//        //
//        // Summary:
//        //     Identical to INTERNET_FLAG_FROM_CACHE. Does not make network requests. All entities
//        //     are returned from the cache. If the requested item is not in the cache, a suitable
//        //     error, such as ERROR_FILE_NOT_FOUND, is returned. Only the InternetOpen function
//        //     uses this flag.
//        INTERNET_FLAG_OFFLINE = 16777216,
//        //
//        // Summary:
//        //     No longer supported.
//        INTERNET_FLAG_MAKE_PERSISTENT = 33554432,
//        //
//        // Summary:
//        //     Does not add the returned entity to the cache. This is identical to the preferred
//        //     value, INTERNET_FLAG_NO_CACHE_WRITE.
//        INTERNET_FLAG_DONT_CACHE = 67108864,
//        //
//        // Summary:
//        //     Does not add the returned entity to the cache. This flag is used by , HttpOpenRequest,
//        //     and InternetOpenUrl.
//        //     Windows XP and Windows Server 2003 R2 and earlier: Also used by GopherFindFirstFile
//        //     and GopherOpenFile.
//        INTERNET_FLAG_NO_CACHE_WRITE = 67108864,
//        //
//        // Summary:
//        //     Uses passive FTP semantics. Only InternetConnect and InternetOpenUrl use this
//        //     flag. InternetConnect uses this flag for FTP requests, and InternetOpenUrl uses
//        //     this flag for FTP files and directories.
//        INTERNET_FLAG_PASSIVE = 134217728,
//        //
//        // Summary:
//        //     Makes only asynchronous requests on handles descended from the handle returned
//        //     from this function. Only the InternetOpen function uses this flag.
//        INTERNET_FLAG_ASYNC = 268435456,
//        //
//        // Summary:
//        //     Attempts to use an existing InternetConnect object if one exists with the same
//        //     attributes required to make the request. This is useful only with FTP operations,
//        //     since FTP is the only protocol that typically performs multiple operations during
//        //     the same session. WinINet caches a single connection handle for each HINTERNET
//        //     handle generated by InternetOpen. The InternetOpenUrl and InternetConnect functions
//        //     use this flag for Http and Ftp connections.
//        INTERNET_FLAG_EXISTING_CONNECT = 536870912,
//        //
//        // Summary:
//        //     Returns the data as a WIN32_FIND_DATA structure when retrieving FTP directory
//        //     information. If this flag is not specified or if the call is made through a CERN
//        //     proxy, InternetOpenUrl returns the HTML version of the directory. Only the InternetOpenUrl
//        //     function uses this flag.
//        //     Windows XP and Windows Server 2003 R2 and earlier: Also returns a GOPHER_FIND_DATA
//        //     structure when retrieving Gopher directory information.
//        INTERNET_FLAG_RAW_DATA = 1073741824,
//        //
//        // Summary:
//        //     Forces a download of the requested file, object, or directory listing from the
//        //     origin server, not from the cache. The FtpFindFirstFile, FtpGetFile, FtpOpenFile,
//        //     FtpPutFile, HttpOpenRequest, and InternetOpenUrl functions use this flag.
//        //     Windows XP and Windows Server 2003 R2 and earlier: Also used by GopherFindFirstFile
//        //     and GopherOpenFile.
//        INTERNET_FLAG_RELOAD = 2147483648
//    }

//    public enum InternetOptionFlags : uint
//    {
//        //
//        // Summary:
//        //     Sets or retrieves the address of the callback function defined for this handle.
//        //     This option can be used on all HINTERNET handles. Used by InternetQueryOption
//        //     and InternetSetOption.
//        INTERNET_OPTION_CALLBACK = 1,
//        //
//        // Summary:
//        //     Sets or retrieves an unsigned long integer value that contains the time-out value,
//        //     in milliseconds, to use for Internet connection requests. Setting this option
//        //     to infinite (0xFFFFFFFF) will disable this timer. If a connection request takes
//        //     longer than this time-out value, the request is canceled. When attempting to
//        //     connect to multiple IP addresses for a single host (a multihome host), the timeout
//        //     limit is cumulative for all of the IP addresses. This option can be used on any
//        //     HINTERNET handle, including a NULL handle. It is used by InternetQueryOption
//        //     and InternetSetOption.
//        INTERNET_OPTION_CONNECT_TIMEOUT = 2,
//        //
//        // Summary:
//        //     Sets or retrieves an unsigned long integer value that contains the number of
//        //     times WinINet attempts to resolve and connect to a host. It only attempts once
//        //     per IP address. For example, if you attempt to connect to a multihome host that
//        //     has ten IP addresses and INTERNET_OPTION_CONNECT_RETRIES is set to seven, WinINet
//        //     only attempts to resolve and connect to the first seven IP addresses. Conversely,
//        //     given the same set of ten IP addresses, if INTERNET_OPTION_CONNECT_RETRIES is
//        //     set to 20, WinINet attempts each of the ten only once. If a host has only one
//        //     IP address and the first connection attempt fails, there are no further attempts.
//        //     If a connection attempt still fails after the specified number of attempts, the
//        //     request is canceled. The default value for INTERNET_OPTION_CONNECT_RETRIES is
//        //     five attempts. This option can be used on any HINTERNET handle, including a NULL
//        //     handle. It is used by InternetQueryOption and InternetSetOption.
//        INTERNET_OPTION_CONNECT_RETRIES = 3,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_CONNECT_BACKOFF = 4,
//        //
//        // Summary:
//        //     Identical to INTERNET_OPTION_SEND_TIMEOUT. This is used by InternetQueryOption
//        //     and InternetSetOption.
//        INTERNET_OPTION_CONTROL_SEND_TIMEOUT = 5,
//        //
//        // Summary:
//        //     Sets or retrieves an unsigned long integer value, in milliseconds, that contains
//        //     the time-out value to send a request. If the send takes longer than this time-out
//        //     value, the send is canceled. This option can be used on any HINTERNET handle,
//        //     including a NULL handle. It is used by InternetQueryOption and InternetSetOption.
//        //     When used in reference to an FTP transaction, this option refers to the control
//        //     channel.
//        INTERNET_OPTION_SEND_TIMEOUT = 5,
//        //
//        // Summary:
//        //     Identical to INTERNET_OPTION_RECEIVE_TIMEOUT. This is used by InternetQueryOption
//        //     and InternetSetOption.
//        INTERNET_OPTION_CONTROL_RECEIVE_TIMEOUT = 6,
//        //
//        // Summary:
//        //     Sets or retrieves an unsigned long integer value that contains the time-out value,
//        //     in milliseconds, to receive a response to a request. If the response takes longer
//        //     than this time-out value, the request is canceled. This option can be used on
//        //     any HINTERNET handle, including a NULL handle. It is used by InternetQueryOption
//        //     and InternetSetOption. This option is not intended to represent a fine-grained,
//        //     immediate timeout. You can expect the timeout to occur up to six seconds after
//        //     the set timeout value. When used in reference to an FTP transaction, this option
//        //     refers to the control channel.
//        INTERNET_OPTION_RECEIVE_TIMEOUT = 6,
//        //
//        // Summary:
//        //     Sets or retrieves an unsigned long integer value, in milliseconds, that contains
//        //     the time-out value to send a request for the data channel of an FTP transaction.
//        //     If the send takes longer than this time-out value, the send is canceled. This
//        //     option can be used on any HINTERNET handle, including a NULL handle. It is used
//        //     by InternetQueryOption and InternetSetOption. This flag has no impact on HTTP
//        //     functionality.
//        INTERNET_OPTION_DATA_SEND_TIMEOUT = 7,
//        //
//        // Summary:
//        //     Sets or retrieves an unsigned long integer value that contains the time-out value,
//        //     in milliseconds, to receive a response to a request for the data channel of an
//        //     FTP transaction. If the response takes longer than this time-out value, the request
//        //     is canceled. This option can be used on any HINTERNET handle, including a NULL
//        //     handle. It is used by InternetQueryOption and InternetSetOption. This flag has
//        //     no impact on HTTP functionality.
//        INTERNET_OPTION_DATA_RECEIVE_TIMEOUT = 8,
//        //
//        // Summary:
//        //     Retrieves an unsigned long integer value that contains the type of the HINTERNET
//        //     handles passed in. This is used by InternetQueryOption on any HINTERNET handle.
//        //     Returns a InternetOptionHandleType value.
//        INTERNET_OPTION_HANDLE_TYPE = 9,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_LISTEN_TIMEOUT = 11,
//        //
//        // Summary:
//        //     Sets or retrieves an unsigned long integer value that contains the size of the
//        //     read buffer. This option can be used on HINTERNET handles returned by FtpOpenFile,
//        //     FtpFindFirstFile, and InternetConnect (FTP session only). This option is used
//        //     by InternetQueryOption and InternetSetOption.
//        INTERNET_OPTION_READ_BUFFER_SIZE = 12,
//        //
//        // Summary:
//        //     Sets or retrieves an unsigned long integer value that contains the size, in bytes,
//        //     of the write buffer. This option can be used on HINTERNET handles returned by
//        //     FtpOpenFile and InternetConnect (FTP session only). It is used by InternetQueryOption
//        //     and InternetSetOption.
//        INTERNET_OPTION_WRITE_BUFFER_SIZE = 13,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_ASYNC_ID = 15,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_ASYNC_PRIORITY = 16,
//        //
//        // Summary:
//        //     Retrieves the parent handle to this handle. This option can be used on any HINTERNET
//        //     handle by InternetQueryOption.
//        INTERNET_OPTION_PARENT_HANDLE = 21,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_KEEP_CONNECTION = 22,
//        //
//        // Summary:
//        //     Retrieves an unsigned long integer value that contains the special status flags
//        //     that indicate the status of the download in progress. This is used by InternetQueryOption.
//        INTERNET_OPTION_REQUEST_FLAGS = 23,
//        //
//        // Summary:
//        //     Retrieves an unsigned long integer value that contains a Winsock error code mapped
//        //     to the ERROR_INTERNET_ error messages last returned in this thread context. This
//        //     option is used on a NULLHINTERNET handle by InternetQueryOption.
//        INTERNET_OPTION_EXTENDED_ERROR = 24,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_OFFLINE_MODE = 26,
//        //
//        // Summary:
//        //     No longer supported.
//        INTERNET_OPTION_CACHE_STREAM_HANDLE = 27,
//        //
//        // Summary:
//        //     Sets or retrieves a string that contains the user name associated with a handle
//        //     returned by InternetConnect. This is used by InternetQueryOption and InternetSetOption.
//        INTERNET_OPTION_USERNAME = 28,
//        //
//        // Summary:
//        //     Sets or retrieves a string value that contains the password associated with a
//        //     handle returned by InternetConnect. This is used by InternetQueryOption and InternetSetOption.
//        INTERNET_OPTION_PASSWORD = 29,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_ASYNC = 30,
//        //
//        // Summary:
//        //     Retrieves an unsigned long integer value that contains the security flags for
//        //     a handle. This option is used by InternetQueryOption. It can be a combination
//        //     of the following values.
//        INTERNET_OPTION_SECURITY_FLAGS = 31,
//        //
//        // Summary:
//        //     Retrieves the certificate for an SSL/PCT server into the INTERNET_CERTIFICATE_INFO
//        //     structure. This is used by InternetQueryOption.
//        INTERNET_OPTION_SECURITY_CERTIFICATE_STRUCT = 32,
//        //
//        // Summary:
//        //     Retrieves a string value that contains the name of the file backing a downloaded
//        //     entity. This flag is valid after InternetOpenUrl, FtpOpenFile, GopherOpenFile,
//        //     or HttpOpenRequest has completed. This option can only be queried by InternetQueryOption.
//        INTERNET_OPTION_DATAFILE_NAME = 33,
//        //
//        // Summary:
//        //     Retrieves a string value that contains the full URL of a downloaded resource.
//        //     If the original URL contained any extra data, such as search strings or anchors,
//        //     or if the call was redirected, the URL returned differs from the original. This
//        //     option is valid on HINTERNET handles returned by InternetOpenUrl, FtpOpenFile,
//        //     GopherOpenFile, or HttpOpenRequest. It is used by InternetQueryOption.
//        INTERNET_OPTION_URL = 34,
//        //
//        // Summary:
//        //     Retrieves the certificate for an SSL/PCT (Secure Sockets Layer/Private Communications
//        //     Technology) server into a formatted string. This is used by InternetQueryOption.
//        INTERNET_OPTION_SECURITY_CERTIFICATE = 35,
//        //
//        // Summary:
//        //     Retrieves an unsigned long integer value that contains the bit size of the encryption
//        //     key. The larger the number, the greater the encryption strength used. This is
//        //     used by InternetQueryOption. Be aware that the data retrieved this way relates
//        //     to a transaction that has already occurred, whose security level can no longer
//        //     be changed.
//        INTERNET_OPTION_SECURITY_KEY_BITNESS = 36,
//        //
//        // Summary:
//        //     Causes the proxy data to be reread from the registry for a handle. No buffer
//        //     is required. This option can be used on the HINTERNET handle returned by InternetOpen.
//        //     It is used by InternetSetOption.
//        INTERNET_OPTION_REFRESH = 37,
//        //
//        // Summary:
//        //     Sets or retrieves an INTERNET_PROXY_INFO structure that contains the proxy data
//        //     for an existing InternetOpen handle when the HINTERNET handle is not NULL. If
//        //     the HINTERNET handle is NULL, the function sets or queries the global proxy data.
//        //     This option can be used on the handle returned by InternetOpen. It is used by
//        //     InternetQueryOption and InternetSetOption. Note It is recommended that INTERNET_OPTION_PER_CONNECTION_OPTION
//        //     be used instead of INTERNET_OPTION_PROXY. For more information, see KB article
//        //     226473.
//        INTERNET_OPTION_PROXY = 38,
//        //
//        // Summary:
//        //     Notifies the system that the registry settings have been changed so that it verifies
//        //     the settings on the next call to InternetConnect. This is used by InternetSetOption.
//        INTERNET_OPTION_SETTINGS_CHANGED = 39,
//        //
//        // Summary:
//        //     Retrieves an INTERNET_VERSION_INFO structure that contains the version number
//        //     of WinInet.dll. This option can be used on a NULLHINTERNET handle by InternetQueryOption.
//        INTERNET_OPTION_VERSION = 40,
//        //
//        // Summary:
//        //     Sets or retrieves the user agent string on handles supplied by InternetOpen and
//        //     used in subsequent HttpSendRequest functions, as long as it is not overridden
//        //     by a header added by HttpAddRequestHeaders or HttpSendRequest. This is used by
//        //     InternetQueryOption and InternetSetOption.
//        INTERNET_OPTION_USER_AGENT = 41,
//        //
//        // Summary:
//        //     Flushes entries not in use from the password cache on the hard disk drive. Also
//        //     resets the cache time used when the synchronization mode is once-per-session.
//        //     No buffer is required for this option. This is used by InternetSetOption.
//        INTERNET_OPTION_END_BROWSER_SESSION = 42,
//        //
//        // Summary:
//        //     Sets or retrieves a string value that contains the user name used to access the
//        //     proxy. This is used by InternetQueryOption and InternetSetOption. This option
//        //     can be set on the handle returned by InternetConnect or HttpOpenRequest.
//        INTERNET_OPTION_PROXY_USERNAME = 43,
//        //
//        // Summary:
//        //     Sets or retrieves a string value that contains the password used to access the
//        //     proxy. This is used by InternetQueryOption and InternetSetOption. This option
//        //     can be set on the handle returned by InternetConnect or HttpOpenRequest.
//        INTERNET_OPTION_PROXY_PASSWORD = 44,
//        //
//        // Summary:
//        //     Sets or retrieves a DWORD_PTR that contains the address of the context value
//        //     associated with this HINTERNET handle. This option can be used on any HINTERNET
//        //     handle. This is used by InternetQueryOption and InternetSetOption. Previously,
//        //     this set the context value to the address stored in the lpBuffer pointer. This
//        //     has been corrected so that the value stored in the buffer is used and the INTERNET_OPTION_CONTEXT_VALUE
//        //     flag is assigned a new value. The old value, 10, has been preserved so that applications
//        //     written for the old behavior are still supported.
//        INTERNET_OPTION_CONTEXT_VALUE = 45,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_POLICY = 48,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_DISCONNECTED_TIMEOUT = 49,
//        //
//        // Summary:
//        //     Sets or retrieves an unsigned long integer value that contains the connected
//        //     state. This is used by InternetQueryOption and InternetSetOption.
//        INTERNET_OPTION_CONNECTED_STATE = 50,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_IDLE_STATE = 51,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_OFFLINE_SEMANTICS = 52,
//        //
//        // Summary:
//        //     Sets or retrieves a string value that contains the secondary cache key. This
//        //     is used by InternetQueryOption and InternetSetOption. This option is reserved
//        //     for internal use only.
//        INTERNET_OPTION_SECONDARY_CACHE_KEY = 53,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_CALLBACK_FILTER = 54,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_CONNECT_TIME = 55,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_SEND_THROUGHPUT = 56,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_RECEIVE_THROUGHPUT = 57,
//        //
//        // Summary:
//        //     Sets or retrieves an unsigned long integer value that contains the priority of
//        //     requests that compete for a connection on an HTTP handle. This is used by InternetQueryOption
//        //     and InternetSetOption.
//        INTERNET_OPTION_REQUEST_PRIORITY = 58,
//        //
//        // Summary:
//        //     Sets or retrieves an HTTP_VERSION_INFO structure that contains the supported
//        //     HTTP version. This must be used on a NULL handle. This is used by InternetQueryOption
//        //     and InternetSetOption. On Windows 7, Windows Server 2008 R2, and later, the value
//        //     of the dwMinorVersion member in the HTTP_VERSION_INFO structure is overridden
//        //     by Internet Explorer settings. EnableHttp1_1 is a registry value under HKLM\Software\Microsoft\InternetExplorer\AdvacnedOptions\HTTP\GENABLE
//        //     controlled by Internet Options set in Internet Explorer for the system. The EnableHttp1_1
//        //     value defaults to 1. The HTTP_VERSION_INFO structure is ignored for any HTTP
//        //     version less than 1.1 if EnableHttp1_1 is set to 1.
//        INTERNET_OPTION_HTTP_VERSION = 59,
//        //
//        // Summary:
//        //     Starts a new cache session for the process. No buffer is required. This is used
//        //     by InternetSetOption. This option is reserved for internal use only.
//        INTERNET_OPTION_RESET_URLCACHE_SESSION = 60,
//        //
//        // Summary:
//        //     Sets an unsigned long integer value that contains the error masks that can be
//        //     handled by the client application.
//        INTERNET_OPTION_ERROR_MASK = 62,
//        //
//        // Summary:
//        //     Sets or retrieves a1n unsigned long integer value that contains the amount of
//        //     time the system should wait for a response to a network request before checking
//        //     the cache for a copy of the resource. If a network request takes longer than
//        //     the time specified and the requested resource is available in the cache, the
//        //     resource is retrieved from the cache. This is used by InternetQueryOption and
//        //     InternetSetOption.
//        INTERNET_OPTION_FROM_CACHE_TIMEOUT = 63,
//        //
//        // Summary:
//        //     Sets or retrieves the Boolean value that determines if the system should check
//        //     the network for newer content and overwrite edited cache entries if a newer version
//        //     is found. If set to True, the system checks the network for newer content and
//        //     overwrites the edited cache entry with the newer version. The default is False,
//        //     which indicates that the edited cache entry should be used without checking the
//        //     network. This is used by InternetQueryOption and InternetSetOption. It is valid
//        //     only in Microsoft Internet Explorer 5 and later.
//        INTERNET_OPTION_BYPASS_EDITED_ENTRY = 64,
//        //
//        // Summary:
//        //     Enables WinINet to perform decoding for the gzip and deflate encoding schemes.
//        //     For more information, see Content Encoding.
//        INTERNET_OPTION_HTTP_DECODING = 65,
//        //
//        // Summary:
//        //     Retrieves an INTERNET_DIAGNOSTIC_SOCKET_INFO structure that contains data about
//        //     a specified HTTP Request. This flag is used by InternetQueryOption. Windows 7:
//        //     This option is no longer supported.
//        INTERNET_OPTION_DIAGNOSTIC_SOCKET_INFO = 67,
//        //
//        // Summary:
//        //     By default, the host or authority portion of the Unicode URL is encoded according
//        //     to the IDN specification. Setting this option on the request, or connection handle,
//        //     when IDN is disabled, specifies a code page encoding scheme for the host portion
//        //     of the URL. The lpBuffer parameter in the call to InternetSetOption contains
//        //     the desired DBCS code page. If no code page is specified in lpBuffer, WinINet
//        //     uses the default system code page (CP_ACP). Note: This option is ignored if IDN
//        //     is not disabled. For more information about how to disable IDN, see the INTERNET_OPTION_IDN
//        //     option.
//        //     Windows XP with SP2 and Windows Server 2003 with SP1: This flag is not supported.
//        //     Version: Requires Internet Explorer 7.0.
//        INTERNET_OPTION_CODEPAGE = 68,
//        //
//        // Summary:
//        //     Retrieves an INTERNET_CACHE_TIMESTAMPS structure that contains the LastModified
//        //     time and Expires time from the resource stored in the Internet cache. This value
//        //     is used by InternetQueryOption.
//        INTERNET_OPTION_CACHE_TIMESTAMPS = 69,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_DISABLE_AUTODIAL = 70,
//        //
//        // Summary:
//        //     Sets or retrieves an unsigned long integer value that contains the maximum number
//        //     of connections allowed per server. This is used by InternetQueryOption and InternetSetOption.
//        //     This option is only valid in Internet Explorer 5 and later.
//        INTERNET_OPTION_MAX_CONNS_PER_SERVER = 73,
//        //
//        // Summary:
//        //     Sets or retrieves an unsigned long integer value that contains the maximum number
//        //     of connections allowed per HTTP/1.0 server. This is used by InternetQueryOption
//        //     and InternetSetOption. This option is only valid in Internet Explorer 5 and later.
//        INTERNET_OPTION_MAX_CONNS_PER_1_0_SERVER = 74,
//        //
//        // Summary:
//        //     Sets or retrieves an INTERNET_PER_CONN_OPTION_LIST structure that specifies a
//        //     list of options for a particular connection. This is used by InternetQueryOption
//        //     and InternetSetOption. This option is only valid in Internet Explorer 5 and later.
//        //     Note INTERNET_OPTION_PER_CONNECTION_OPTION causes the settings to be changed
//        //     on a system-wide basis when a NULL handle is used in the call to InternetSetOption.
//        //     To refresh the global proxy settings, you must call InternetSetOption with the
//        //     INTERNET_OPTION_REFRESH option flag. Note To change proxy information for the
//        //     entire process without affecting the global settings in Internet Explorer 5 and
//        //     later, use this option on the handle that is returned from InternetOpen. The
//        //     following code example changes the proxy for the whole process even though the
//        //     HINTERNET handle is closed and is not used by any requests. For more information
//        //     and code examples, see KB article 226473.
//        INTERNET_OPTION_PER_CONNECTION_OPTION = 75,
//        //
//        // Summary:
//        //     Causes the system to log off the Digest authentication SSPI package, purging
//        //     all of the credentials created for the process. No buffer is required for this
//        //     option. It is used by InternetSetOption.
//        INTERNET_OPTION_DIGEST_AUTH_UNLOAD = 76,
//        //
//        // Summary:
//        //     Sets or retrieves whether the global offline flag should be ignored for the specified
//        //     request handle. No buffer is required for this option. This is used by InternetQueryOption
//        //     and InternetSetOption with a request handle. This option is only valid in Internet
//        //     Explorer 5 and later.
//        INTERNET_OPTION_IGNORE_OFFLINE = 77,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_IDENTITY = 78,
//        //
//        // Summary:
//        //     Not implemented.
//        INTERNET_OPTION_REMOVE_IDENTITY = 79,
//        //
//        // Summary:
//        //     Not implemented
//        INTERNET_OPTION_ALTER_IDENTITY = 80,
//        //
//        // Summary:
//        //     A general purpose option that is used to suppress behaviors on a process-wide
//        //     basis. The lpBuffer parameter of the function must be a pointer to a DWORD containing
//        //     the specific behavior to suppress. This option cannot be queried with InternetQueryOption.
//        //     The permitted values are:
//        INTERNET_OPTION_SUPPRESS_BEHAVIOR = 81,
//        //
//        // Summary:
//        //     This flag is not supported by InternetQueryOption. The lpBuffer parameter must
//        //     be a pointer to a CERT_CONTEXT structure and not a pointer to a CERT_CONTEXT
//        //     pointer. If an application receives ERROR_INTERNET_CLIENT_AUTH_CERT_NEEDED, it
//        //     must call InternetErrorDlg or use InternetSetOption to supply a certificate before
//        //     retrying the request. CertDuplicateCertificateContext is then called so that
//        //     the certificate context passed can be independently released by the application.
//        INTERNET_OPTION_CLIENT_CERT_CONTEXT = 84,
//        //
//        // Summary:
//        //     Alerts the current WinInet instance that proxy settings have changed and that
//        //     they must update with the new settings. To alert all available WinInet instances,
//        //     set the Buffer parameter of InternetSetOption to NULL and BufferLength to 0 when
//        //     passing this option. This option can be set on the handle returned by InternetConnect
//        //     or HttpOpenRequest.
//        INTERNET_OPTION_PROXY_SETTINGS_CHANGED = 95,
//        //
//        // Summary:
//        //     Sets a string value that contains the extension of the file backing a downloaded
//        //     entity. This flag should be set before calling InternetOpenUrl, FtpOpenFile,
//        //     GopherOpenFile, or HttpOpenRequest. This option can only be set by InternetSetOption.
//        INTERNET_OPTION_DATAFILE_EXT = 96,
//        //
//        // Summary:
//        //     By default, the path portion of the URL is UTF8 encoded. The WinINet API performs
//        //     escape character (%) encoding on the high-bit characters. Setting this option
//        //     on the request, or connection handle, disables the UTF8 encoding and sets a specific
//        //     code page. The lpBuffer parameter in the call to InternetSetOption contains the
//        //     desired DBCS codepage for the path. If no code page is specified in lpBuffer,
//        //     WinINet uses the default CP_UTF8.
//        //     Windows XP with SP2 and Windows Server 2003 with SP1: This flag is not supported.
//        //     Version: Requires Internet Explorer 7.0.
//        INTERNET_OPTION_CODEPAGE_PATH = 100,
//        //
//        // Summary:
//        //     By default, the path portion of the URL is the default system code page (CP_ACP).
//        //     The escape character (%) conversions are not performed on the extra portion.
//        //     Setting this option on the request, or connection handle disables the CP_ACP
//        //     encoding. The lpBuffer parameter in the call to InternetSetOption contains the
//        //     desired DBCS codepage for the extra portion of the URL. If no code page is specified
//        //     in lpBuffer, WinINet uses the default system code page (CP_ACP).
//        //     Windows XP with SP2 and Windows Server 2003 with SP1: This flag is not supported.
//        //     Version: Requires Internet Explorer 7.0.
//        INTERNET_OPTION_CODEPAGE_EXTRA = 101,
//        //
//        // Summary:
//        //     By default, the host or authority portion of the URL is encoded according to
//        //     the IDN specification for both direct and proxy connections. This option can
//        //     be used on the request, or connection handle to enable or disable IDN. When IDN
//        //     is disabled, WinINet uses the system codepage to encode the host or authority
//        //     portion of the URL. To disable IDN host conversion, set the lpBuffer parameter
//        //     in the call to InternetSetOption to zero. To enable IDN conversion on only the
//        //     direct connection, specify INTERNET_FLAG_IDN_DIRECT in the lpBuffer parameter
//        //     in the call to InternetSetOption. To enable IDN conversion on only the proxy
//        //     connection, specify INTERNET_FLAG_IDN_PROXY in the lpBuffer parameter in the
//        //     call to InternetSetOption. Windows XP with SP2 and Windows Server 2003 with SP1:
//        //     This flag is not supported. Version: Requires Internet Explorer 7.0.
//        INTERNET_OPTION_IDN = 102,
//        //
//        // Summary:
//        //     Sets or retrieves an unsigned long integer value that contains the maximum number
//        //     of connections allowed per CERN proxy. When this option is set or retrieved,
//        //     the hInternet parameter must set to a null handle value. A null handle value
//        //     indicates that the option should be set or queried for the current process. When
//        //     calling InternetSetOption with this option, all existing proxy objects will receive
//        //     the new value. This value is limited to a range of 2 to 128, inclusive. Version:
//        //     Requires Internet Explorer 8.0.
//        INTERNET_OPTION_MAX_CONNS_PER_PROXY = 103,
//        //
//        // Summary:
//        //     Sets an HTTP request object such that it will not logon to origin servers, but
//        //     will perform automatic logon to HTTP proxy servers. This option differs from
//        //     the Request flag INTERNET_FLAG_NO_AUTH, which prevents authentication to both
//        //     proxy servers and origin servers. Setting this mode will suppress the use of
//        //     any credential material (either previously provided username/password or client
//        //     SSL certificate) when communicating with an origin server. However, if the request
//        //     must transit via an authenticating proxy, WinINet will still perform automatic
//        //     authentication to the HTTP proxy per the Intranet Zone settings for the user.
//        //     The default Intranet Zone setting is to permit automatic logon using the user’s
//        //     default credentials. To ensure suppression of all identifying information, the
//        //     caller should combine INTERNET_OPTION_SUPPRESS_SERVER_AUTH with the INTERNET_FLAG_NO_COOKIES
//        //     request flag. This option may only be set on request objects before they have
//        //     been sent. Attempts to set this option after the request has been sent will return
//        //     ERROR_INTERNET_INCORRECT_HANDLE_STATE. No buffer is required for this option.
//        //     This is used by InternetSetOption on handles returned by HttpOpenRequest only.
//        //     Version: Requires Internet Explorer 8.0 or later.
//        INTERNET_OPTION_SUPPRESS_SERVER_AUTH = 104,
//        //
//        // Summary:
//        //     Retrieves the server’s certificate-chain context as a duplicated PCCERT_CHAIN_CONTEXT.
//        //     You may pass this duplicated context to any Crypto API function which takes a
//        //     PCCERT_CHAIN_CONTEXT. You must call CertFreeCertificateChain on the returned
//        //     PCCERT_CHAIN_CONTEXT when you are done with the certificate-chain context. Version:
//        //     Requires Internet Explorer 8.0.
//        INTERNET_OPTION_SERVER_CERT_CHAIN_CONTEXT = 105,
//        //
//        // Summary:
//        //     On a request handle, sets a Boolean controlling whether redirects will be returned
//        //     from the WinInet cache for a given request. The default is FALSE. Supported in
//        //     Windows 8 and later.
//        INTERNET_OPTION_ENABLE_REDIRECT_CACHE_READ = 122,
//        //
//        // Summary:
//        //     For a request where WinInet decompressed the server’s supplied Content-Encoding,
//        //     retrieves the server-reported Content-Length of the response body as a ULONGLONG.
//        //     Supported in Windows 10, version 1507 and later.
//        INTERNET_OPTION_COMPRESSED_CONTENT_LENGTH = 147,
//        //
//        // Summary:
//        //     Sets a DWORD bitmask of acceptable advanced HTTP versions. May be set on any
//        //     handle type. Possible values are: HTTP_PROTOCOL_FLAG_HTTP2 (0x2). Supported on
//        //     Windows 10, version 1507 and later. Legacy versions of HTTP (1.1 and prior) cannot
//        //     be disabled using this option. The default is 0x0. Supported in Windows 10, version
//        //     1507 and later.
//        INTERNET_OPTION_ENABLE_HTTP_PROTOCOL = 148,
//        //
//        // Summary:
//        //     Gets a DWORD indicating which advanced HTTP version was used on a given request.
//        //     Possible values are: HTTP_PROTOCOL_FLAG_HTTP2 (0x2). Supported on Windows 10,
//        //     version 1507 and later. 0x0 indicates HTTP/1.1 or earlier; see INTERNET_OPTION_HTTP_VERSION
//        //     if more precision is needed about which legacy version was used. Supported on
//        //     Windows 10, version 1507 and later.
//        INTERNET_OPTION_HTTP_PROTOCOL_USED = 149,
//        //
//        // Summary:
//        //     Gets/sets a BOOL indicating whether non-ASCII characters in the query string
//        //     should be percent-encoded. The default is FALSE. Supported in Windows 8.1 and
//        //     later.
//        INTERNET_OPTION_ENCODE_EXTRA = 155,
//        //
//        // Summary:
//        //     Gets/sets a BOOL indicating whether WinInet should follow HTTP Strict Transport
//        //     Security (HSTS) directives from servers. If enabled, http:// schemed requests
//        //     to domains which have an HSTS policy cached by WinInet will be redirected to
//        //     matching https:// URLs. The default is FALSE. Supported in Windows 8.1 and later.
//        INTERNET_OPTION_HSTS = 157,
//        //
//        // Summary:
//        //     Sets a PWSTR containing the Enterprise ID (see https://msdn.microsoft.com/en-us/library/windows/desktop/mt759320(v=vs.85).aspx)
//        //     which applies to the request. Supported in Windows 10, version 1507 and later.
//        INTERNET_OPTION_ENTERPRISE_CONTEXT = 159,
//        //
//        // Summary:
//        //     Opt-in for weak signatures (e.g. SHA-1) to be treated as insecure. This will
//        //     instruct WinInet to call CertGetCertificateChain using the CERT_CHAIN_OPT_IN_WEAK_SIGNATURE
//        //     parameter.
//        INTERNET_OPTION_OPT_IN_WEAK_SIGNATURE = 176,
//        //
//        // Summary:
//        //     The bit size used in the encryption is unknown. This is only returned in a call
//        //     to InternetQueryOption. Be aware that the data retrieved this way relates to
//        //     a transaction that has occurred, whose security level can no longer be changed.
//        SECURITY_FLAG_UNKNOWNBIT = 2147483648
//    }
//}