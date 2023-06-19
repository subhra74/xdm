using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.IO;
using System.Net;
using XDM.Core;
using static Interop.WinHttp;
using System.Runtime.InteropServices;
using TraceLog;
using System.Net.Http;

namespace XDM.Core.Clients.Http
{
    internal class WinHttpClient : IHttpClient
    {
        private SafeWinHttpHandle? sessionHandle;
        private bool disposed;
        private ProxyInfo? proxy;

        public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(100);

        internal WinHttpClient(ProxyInfo? proxy)
        {
            this.proxy = proxy;
        }

        private HttpRequest CreateRequest(
            Uri uri,
            string method,
            Dictionary<string, List<string>>? headers = null,
            string? cookies = null,
            AuthenticationInfo? authentication = null,
            byte[]? body = null)
        {
            if (disposed)
            {
                throw new ObjectDisposedException("HttpWebRequestClient");
            }

            lock (this)
            {
                if (this.sessionHandle == null)
                {
                    if (this.proxy.HasValue && this.proxy.Value.ProxyType == ProxyType.System &&
                            GetProxyForUrl(uri, out string? proxy, out string? bypass) && proxy != null)
                    {
                        sessionHandle = WinHttpOpen(
                                IntPtr.Zero,
                                WINHTTP_ACCESS_TYPE_NAMED_PROXY,
                                proxy,
                                bypass,
                                0);
                    }
                    else if (this.proxy.HasValue && this.proxy.Value.ProxyType == ProxyType.Custom)
                    {
                        sessionHandle = WinHttpOpen(
                                IntPtr.Zero,
                                WINHTTP_ACCESS_TYPE_NAMED_PROXY,
                                "http://" + this.proxy.Value.Host + ":" + this.proxy.Value.Port,
                                WINHTTP_NO_PROXY_BYPASS,
                                0);
                    }
                    else
                    {
                        sessionHandle = WinHttpOpen(
                                IntPtr.Zero,
                                WINHTTP_ACCESS_TYPE_NO_PROXY,
                                WINHTTP_NO_PROXY_NAME,
                                WINHTTP_NO_PROXY_BYPASS,
                                0);
                    }

                    if (sessionHandle.IsInvalid)
                    {
                        throw new IOException(nameof(sessionHandle) + " " + Marshal.GetLastWin32Error());
                    }

                    uint optionData = 100;
                    if (!WinHttpSetOption(sessionHandle, WINHTTP_OPTION_MAX_CONNS_PER_SERVER, ref optionData))
                    {
                        Log.Debug("Unable to set connection limit");
                    }

                    //const uint WINHTTP_FLAG_SECURE_PROTOCOL_SSL2 = 0x00000008;
                    //const uint WINHTTP_FLAG_SECURE_PROTOCOL_SSL3 = 0x00000020;
                    //const uint WINHTTP_FLAG_SECURE_PROTOCOL_TLS1 = 0x00000080;
                    //const uint WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_1 = 0x00000200;
                    //const uint WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_2 = 0x00000800;
                    //const uint WINHTTP_FLAG_SECURE_PROTOCOL_ALL = (WINHTTP_FLAG_SECURE_PROTOCOL_SSL2 | WINHTTP_FLAG_SECURE_PROTOCOL_SSL3 | WINHTTP_FLAG_SECURE_PROTOCOL_TLS1);

                    var proto = WINHTTP_FLAG_SECURE_PROTOCOL_TLS1 | WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_1 | WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_2;
                    if (!WinHttpSetOption(sessionHandle, WINHTTP_OPTION_SECURE_PROTOCOLS, ref proto))
                    {
                        Log.Debug("WINHTTP_OPTION_SECURE_PROTOCOLS not set: " + Marshal.GetLastWin32Error());
                    }

                    var timeout = (int)Timeout.TotalMilliseconds;
                    if (!WinHttpSetTimeouts(sessionHandle, timeout, timeout, timeout, timeout))
                    {
                        Log.Debug("Set WinHttpSetTimeouts failed: " + Marshal.GetLastWin32Error());
                    }
                }
            }

            var hConnect = WinHttpConnect(sessionHandle, uri.Host, (ushort)uri.Port, 0);
            if (hConnect.IsInvalid)
            {
                throw new IOException(nameof(hConnect));
            }
            var hRequest = WinHttpOpenRequest(hConnect, method, uri.PathAndQuery,
                null, null, null,
                uri.Scheme == "https" ? WINHTTP_FLAG_ESCAPE_DISABLE | WINHTTP_FLAG_SECURE : WINHTTP_FLAG_ESCAPE_DISABLE);
            if (hRequest.IsInvalid)
            {
                throw new IOException(nameof(hRequest));
            }

            var dwFlags = SECURITY_FLAG_IGNORE_UNKNOWN_CA |
                                SECURITY_FLAG_IGNORE_CERT_WRONG_USAGE |
                                SECURITY_FLAG_IGNORE_CERT_CN_INVALID |
                                SECURITY_FLAG_IGNORE_CERT_DATE_INVALID;

            if (!WinHttpSetOption(
                hRequest,
                WINHTTP_OPTION_SECURITY_FLAGS,
                 ref dwFlags))
            {
                Log.Debug("Ignore cert error: " + Marshal.GetLastWin32Error());
            }
            else
            {
                Log.Debug("Ignore cert error config set");
            }

            if (authentication.HasValue && !string.IsNullOrEmpty(authentication.Value.UserName))
            {
                if (!WinHttpSetCredentials(hRequest, WINHTTP_AUTH_TARGET_SERVER,
                 WINHTTP_AUTH_SCHEME_BASIC, authentication.Value.UserName, authentication.Value.Password, IntPtr.Zero))
                {
                    Log.Debug("Error WinHttpSetCredentials - server: " + Marshal.GetLastWin32Error());
                }
            }

            if (this.proxy.HasValue && !string.IsNullOrEmpty(this.proxy.Value.UserName))
            {
                if (!WinHttpSetCredentials(hRequest, WINHTTP_AUTH_TARGET_PROXY,
                 WINHTTP_AUTH_SCHEME_BASIC, authentication.Value.UserName, authentication.Value.Password, IntPtr.Zero))
                {
                    Log.Debug("Error WinHttpSetCredentials - proxy: " + Marshal.GetLastWin32Error());
                }
            }

            //dwFlags = WINHTTP_FLAG_SECURE_PROTOCOL_SSL3 | WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_1;

            //if (!WinHttpSetOption(
            //   hConnect,
            //   WINHTTP_OPTION_SECURE_PROTOCOLS,
            //    ref dwFlags))
            //{
            //    Log.Debug("WINHTTP_OPTION_SECURE_PROTOCOLS: " + Marshal.GetLastWin32Error());
            //}
            //else
            //{
            //    Log.Debug("WINHTTP_OPTION_SECURE_PROTOCOLS set");
            //}

            return new HttpRequest { Session = new WinHttpSession(uri, hConnect, hRequest, headers, cookies) };
        }

        public HttpRequest CreateGetRequest(
            Uri uri,
            Dictionary<string, List<string>>? headers = null,
            string? cookies = null,
            AuthenticationInfo? authentication = null)
        {
            return CreateRequest(uri, "GET", headers, cookies, authentication);
        }

        public HttpRequest CreatePostRequest(
            Uri uri,
            Dictionary<string, List<string>>? headers = null,
            string? cookies = null,
            AuthenticationInfo? authentication = null,
            byte[]? body = null)
        {
            return CreateRequest(uri, "POST", headers, cookies, authentication, body);
        }

        public void Dispose()
        {
            disposed = true;
            this.sessionHandle?.Close();
        }

        public void Close()
        {
            Dispose();
        }

        public HttpResponse Send(HttpRequest request)
        {
            var session = (WinHttpSession)request.Session!;
            var headerBuf = PrepareHeaders(session.Headers, session.Cookies, session.RangeStart, session.RangeEnd);
            if (headerBuf.Length > 0)
            {
                if (!WinHttpAddRequestHeaders(session.RequestHandle, headerBuf, (uint)headerBuf.Length, WINHTTP_ADDREQ_FLAG_ADD))
                {
                    Log.Debug("Unable to set headers");
                }
            }
            var lpOptional = IntPtr.Zero;
            var dwOptionalLength = 0;
            if (session.PostData.HasValue)
            {
                lpOptional = session.PostData.Value;
                dwOptionalLength = session.PostDataSize;
            }
            if (WinHttpSendRequest(session.RequestHandle, IntPtr.Zero, 0, lpOptional,
                (uint)dwOptionalLength, (uint)dwOptionalLength, IntPtr.Zero))
            {
                if (WinHttpReceiveResponse(session.RequestHandle, IntPtr.Zero))
                {
                    var headerSize = CalculateHeaderBufferSize(session.RequestHandle);
                    if (headerSize > 0)
                    {
                        var headers = GetHeaders(session.RequestHandle, headerSize);
                        session.SetHeaders(headers);
                        return new HttpResponse { Session = session };
                    }
                    else
                    {
                        Log.Debug("Failed CalculateHeaderBufferSize: " + Marshal.GetLastWin32Error());
                    }
                }
                else
                {
                    Log.Debug("Failed WinHttpReceiveResponse: " + Marshal.GetLastWin32Error());
                }
            }
            else
            {
                Log.Debug("Failed WinHttpSendRequest: " + Marshal.GetLastWin32Error());
            }
            throw new Exception();
        }

        private uint CalculateHeaderBufferSize(SafeWinHttpHandle hRequest)
        {
            uint bufferLengthInBytes = 0;
            uint index = 0;
            if (!WinHttpQueryHeaders(
                hRequest,
                WINHTTP_QUERY_RAW_HEADERS_CRLF,
                WINHTTP_HEADER_NAME_BY_INDEX,
                IntPtr.Zero,
                ref bufferLengthInBytes,
                ref index) && Marshal.GetLastWin32Error() == ERROR_INSUFFICIENT_BUFFER)
            {
                return bufferLengthInBytes;
            }
            return 0;
        }

        private string GetHeaders(SafeWinHttpHandle hRequest, uint bufferLengthInBytes)
        {
            uint index = 0;
            var pBuf = Marshal.AllocHGlobal((int)bufferLengthInBytes);
            if (!WinHttpQueryHeaders(
                hRequest,
                WINHTTP_QUERY_RAW_HEADERS_CRLF,
                WINHTTP_HEADER_NAME_BY_INDEX,
                pBuf,
                ref bufferLengthInBytes,
                ref index))
            {
                throw new IOException("Unable to read headers: " + Marshal.GetLastWin32Error());
            }
            Log.Debug("Header len after read: " + bufferLengthInBytes);
            var header = Marshal.PtrToStringAuto(pBuf);
            Marshal.FreeHGlobal(pBuf);
            return header;
        }

        private StringBuilder PrepareHeaders(Dictionary<string, List<string>>? headers = null,
            string? cookies = null,
            long rangeStart = -1, long rangeEnd = -1)
        {
            var buf = new StringBuilder();
            if (headers != null)
            {
                foreach (var key in headers.Keys)
                {
                    buf.Append(key).Append(": ").Append(string.Join(", ", headers[key].ToArray())).Append("\r\n");
                }
            }
            if (cookies != null)
            {
                buf.Append($"Cookie: {cookies}\r\n");
            }
            if (rangeStart > 0 && rangeEnd > 0)
            {
                buf.Append(string.Format("Range: bytes={0}-{1}\r\n", rangeStart, rangeEnd));
            }
            else if (rangeStart > 0)
            {
                buf.Append(string.Format("Range: bytes={0}-\r\n", rangeStart));
            }
            return buf;
        }

        private bool GetProxyForUrl(Uri url, out string? proxyHost, out string? bypass)
        {
            proxyHost = null;
            bypass = null;
            var handle = WinHttpOpen(
                            IntPtr.Zero,
                            WINHTTP_ACCESS_TYPE_NO_PROXY,
                            WINHTTP_NO_PROXY_NAME,
                            WINHTTP_NO_PROXY_BYPASS,
                            0);
            try
            {
                var _proxyHelper = new WinInetProxyHelper();
                if (_proxyHelper.GetProxyForUrl(handle, url, out WINHTTP_PROXY_INFO info))
                {
                    proxyHost = Marshal.PtrToStringAuto(info.Proxy);
                    bypass = Marshal.PtrToStringAuto(info.ProxyBypass);
                    return true;
                }
                return false;
            }
            finally
            {
                handle.Close();
            }
        }
    }
}
