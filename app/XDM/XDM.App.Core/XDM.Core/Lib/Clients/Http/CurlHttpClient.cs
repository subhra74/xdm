//using System;
//using System.Collections.Generic;
//using System.Linq;
//using System.Runtime.InteropServices;
//using System.Text;
//using System.Threading;
//using XDM.Core.Interop.CURL;
//using XDM.Core.Lib.Common;

//namespace XDM.Core.Lib.Clients.Http
//{
//    internal class CurlHttpClient : IHttpClient
//    {
//        private CancelFlag cancelFlag = new CancelFlag();
//        private Thread multiRunner;
//        private bool disposed;
//        private Queue<IntPtr> resumeQueue = new();
//        private Dictionary<IntPtr, CurlSession> sessions = new();

//        public TimeSpan Timeout { get => throw new NotImplementedException(); set => throw new NotImplementedException(); }

//        public CurlHttpClient()
//        {
//            this.multiRunner = new Thread(RunCurlMulti);
//            this.multiRunner.Start();
//        }

//        public void Close()
//        {
//            cancelFlag.Cancel();
//        }

//        public HttpRequest CreateGetRequest(Uri uri, string method, Dictionary<string, List<string>>? headers = null, Dictionary<string, string>? cookies = null, AuthenticationInfo? authentication = null)
//        {
//            if (disposed)
//            {
//                throw new ObjectDisposedException("HttpWebRequestClient");
//            }

//            return new HttpRequest { /*Session = new CurlSession()*/ };
//        }

//        public HttpRequest CreateGetRequest(Uri uri, Dictionary<string, List<string>>? headers = null, Dictionary<string, string>? cookies = null, AuthenticationInfo? authentication = null)
//        {
//            if (disposed)
//            {
//                throw new ObjectDisposedException("HttpWebRequestClient");
//            }
//            throw new NotImplementedException();
//        }

//        public HttpRequest CreatePostRequest(Uri uri, Dictionary<string, List<string>>? headers = null, Dictionary<string, string>? cookies = null, AuthenticationInfo? authentication = null, byte[]? body = null)
//        {
//            throw new NotImplementedException();
//        }

//        public void Dispose()
//        {
//            cancelFlag.Cancel();
//        }

//        public HttpResponse Send(HttpRequest request)
//        {
//            throw new NotImplementedException();
//        }

//        private void RunCurlMulti()
//        {
//            int still_running = 0;
//            var multi_handle = CurlNative.curl_multi_init();
//            while (!cancelFlag.IsCancellationRequested)
//            {
//                IntPtr msg;
//                int queued = 0;
//                CurlNative.curl_multi_perform(multi_handle, ref still_running);
//                CurlNative.curl_multi_poll(multi_handle, IntPtr.Zero, 0, 50, IntPtr.Zero);

//                do
//                {
//                    msg = CurlNative.curl_multi_info_read(multi_handle, ref queued);
//                    if (msg != IntPtr.Zero)
//                    {
//                        CurlNative.CURLMsg msgStruct = (CurlNative.CURLMsg)Marshal.PtrToStructure(msg, typeof(CurlNative.CURLMsg));
//                        if (msgStruct.msg == CurlNative.CURLMSG_DONE)
//                        {
//                            Console.WriteLine("Finished handle: " + msgStruct.easy_handle);
//                            Console.WriteLine("Result: " + msgStruct.result);
//                            lock (this)
//                            {
//                                if (sessions.TryGetValue(msgStruct.easy_handle, out CurlSession session))
//                                {
//                                    session.Close();
//                                }
//                            }
//                            //if (msgStruct.easy_handle == easy1)
//                            //{
//                            //    Event.Set();
//                            //    continue;
//                            //}
//                            //else
//                            //{
//                            //    Console.WriteLine("UnFinished handle: " + msgStruct.easy_handle);
//                            //}
//                        }
//                    }
//                } while (msg != IntPtr.Zero);

//                lock (this)
//                {
//                    for (var i = 0; i < resumeQueue.Count; i++)
//                    {
//                        var easyHandle = resumeQueue.Dequeue();
//                        CurlNative.curl_easy_pause(easyHandle, CurlNative.CURLPAUSE_CONT);
//                    }
//                }
//            }
//            CurlNative.curl_multi_cleanup(multi_handle);
//        }

//        internal void ResumeSession(IntPtr easyHandle)
//        {
//            lock (this)
//            {
//                resumeQueue.Enqueue(easyHandle);
//            }
//        }
//    }
//}
