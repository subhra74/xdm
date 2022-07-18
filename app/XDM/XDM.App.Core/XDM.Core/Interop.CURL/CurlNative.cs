using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Runtime.InteropServices;

namespace XDM.Core.Interop.CURL
{
    internal class CurlNative
    {
        internal const string LIBCURL = @"libcurl.dll";
        public const int CURLMSG_NONE = 0, /* first, not used */
            CURLMSG_DONE = 1, /* This easy handle has completed. 'result' contains
                   the CURLcode of the transfer */
            CURLMSG_LAST = 2,/* last, not used */
            CURLOPT_URL = 10002,
            CURLOPT_HEADERFUNCTION = 20079,
            CURLOPT_WRITEFUNCTION = 20011,
            CURLOPT_SSL_VERIFYPEER = 64,
            CURLOPT_FOLLOWLOCATION = 52,
            CURL_WRITEFUNC_PAUSE = 0x10000001,
            CURLPAUSE_CONT = 0;

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl, EntryPoint = "curl_version")]
        [return: MarshalAs(UnmanagedType.LPStr)]
        internal static extern string curl_version();

        public const int CURL_GLOBAL_NOTHING = 0, CURL_GLOBAL_SSL = 1 << 0, CURL_GLOBAL_WIN32 = 1 << 1, CURL_GLOBAL_ACK_EINTR = 1 << 2, CURL_GLOBAL_ALL = CURL_GLOBAL_SSL | CURL_GLOBAL_WIN32;

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl)]
        public static extern IntPtr curl_multi_init();

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl)]
        public static extern int curl_multi_cleanup(IntPtr pmulti);

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl)]
        public static extern int curl_global_init(int flags);

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl)]
        public static extern void curl_global_cleanup();

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl)]
        public static extern IntPtr curl_easy_init();

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl)]
        public static extern void curl_easy_cleanup(IntPtr pCurl);

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl)]
        public static extern int curl_multi_perform(IntPtr pmulti, ref int runningHandles);

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl)]
        public static extern int curl_multi_poll(IntPtr multi_handle, IntPtr extra_fds, uint extra_nfds, int timeout_ms, IntPtr numfds);

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl)]
        public static extern IntPtr curl_multi_info_read(IntPtr multi_handle, ref int msgs_in_queue);

        [StructLayout(LayoutKind.Explicit)]
        public struct CURLMsg
        {
            [FieldOffset(0)]
            public int msg;             /* what this message means */
            [FieldOffset(4)]
            public IntPtr easy_handle;  /* the handle it concerns */
            [FieldOffset(8)]
            public IntPtr whatever;     /* message-specific data */
            [FieldOffset(8)]
            public int result;          /* return code for transfer */
        };

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl)]
        public static extern int curl_easy_setopt(IntPtr pCurl, int opt, string parm);

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl)]
        public static extern int curl_easy_setopt(IntPtr pCurl, int opt, long parm);

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl)]
        public static extern int curl_easy_setopt(IntPtr pCurl, int opt, CurlCallback parm);

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl)]
        public static extern int curl_multi_add_handle(IntPtr pmulti, IntPtr peasy);

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl)]
        public static extern int curl_easy_perform(IntPtr pCurl);

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl)]
        public static extern int curl_easy_pause(IntPtr pCurl, int bitmask);

        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        public delegate uint CurlCallback(IntPtr data, uint size, uint nmemb, IntPtr userdata);

        [DllImport(LIBCURL, CallingConvention = CallingConvention.Cdecl)]
        public static extern int curl_easy_getinfo(IntPtr pCurl, int opt, ref IntPtr data);
    }
}
