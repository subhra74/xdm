using System;
using System.Collections.Generic;
using System.Net;
using System.Threading;
using System.IO;
using XDM.Core.Lib.Common;
using TraceLog;
using XDM.Core.Lib.Clients.Http;
using System.Linq;

namespace XDM.Core.Lib.Downloader.Progressive
{
    public class PieceGrabber : IDisposable
    {
        private Thread t;
        private ManualResetEvent sleepHandle = new ManualResetEvent(false);
        private Uri? redirectUri;
        private string? pieceId;
        private IPieceCallback? callback;
        private HttpRequest? request;
        //private readonly byte[] BUF = new byte[32 * 1024];
        private readonly CancelFlag cancellationTokenSource = new();
        private Stream? fileWriterStream;
        private int timesRetried = 0;
        private long maxByteRange = 0; //maximum byte range requested by http client,
                                       //reading past this length will return 0
                                       //and new request needs to be sent
        private long actualHttpResponseSize = -1;

        public CancelFlag CancellationToken => cancellationTokenSource;
        public PieceGrabber(string pieceId, IPieceCallback callback)
        {
            this.pieceId = pieceId;
            this.callback = callback;
        }

        private void OnComplete()
        {
            try
            {
                if (this.pieceId != null)
                {
                    this.callback?.PieceDownloadFinished(this.pieceId);
                }
            }
            catch (AssembleFailedException ex)
            {
                Log.Debug(ex, "Exception in OnComplete");
                throw;
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Exception in OnComplete");
                if (ex is DownloadException exception)
                {
                    throw new AssembleFailedException(exception.ErrorCode, ex);
                }
                throw new AssembleFailedException(ErrorCode.Generic, ex);
            }
        }

        public void Download()
        {
            this.t = new Thread(Download2);
            this.t.Start();
        }

        private void Download2()
        {
            try
            {
                if (this.pieceId == null || this.callback == null) return;
                var piece = this.callback.GetPiece(this.pieceId);
                while (!this.CancellationToken.IsCancellationRequested)
                {
                    var connectPhase = true;
                    try
                    {
                        if (piece.Length < 1 || piece.Downloaded < piece.Length)
                        {
                            using var response = Connect();
                            if (response == null)
                            {
                                throw new Exception("response is null");
                            }
                            connectPhase = false;
                            this.Download(response);
                        }

                        OnComplete();
                        return;
                    }
                    catch (TextRedirectException e)
                    {
                        this.redirectUri = e.RedirectUri;
                        continue;
                    }
                    catch (HttpException e)
                    {
                        var status = e.StatusCode;
                        if (Enum.IsDefined(typeof(HttpStatusCode), status))
                        {
                            throw new DownloadException(ErrorCode.InvalidResponse,
                                "Invalid response: " + e.Message, e);
                        }
                    }
                    catch (Exception e)
                    {
                        if (e is KeyNotFoundException || this.CancellationToken.IsCancellationRequested) return;
                        if (e is AssembleFailedException || e is NonRetriableException || e is OperationCanceledException) throw;
                        Log.Debug(e, "Error in PieceGrabber inner block - swallowing error - isCancelled: " + this.cancellationTokenSource.IsCancellationRequested);
                    }
                    timesRetried++;
                    if (timesRetried > Config.Instance.MaxRetry)
                    {
                        throw new DownloadException(ErrorCode.MaxRetryFailed, "Max retry exceeded");
                    }
                    if (connectPhase)
                    {
                        sleep(Config.Instance.RetryDelay * 1000);
                        CancellationToken.ThrowIfCancellationRequested();
                        //await Task.Delay(Config.Instance.RetryDelay * 1000,
                        //    this.CancellationToken).ConfigureAwait(false);
                    }
                }
            }
            catch (Exception e)
            {
                if (e is KeyNotFoundException || this.CancellationToken.IsCancellationRequested)
                {
                    return;
                }
                Log.Debug(e, "Error in PieceGrabber outer block");
                if (this.pieceId != null)
                {
                    this.callback?.PieceDownloadFailed(this.pieceId,
                        e is DownloadException de ? de.ErrorCode : ErrorCode.Generic);
                }
            }
        }

        public void Stop()
        {
            Log.Debug("Stopping request");
            this.Dispose();
            try { this.request?.Abort(); } catch { }
        }

        public void Dispose()
        {
            this.cancellationTokenSource?.Cancel();
            this.sleepHandle.Set();
            this.sleepHandle.Close();
            this.pieceId = null;
            this.callback = null;
            try { this.fileWriterStream?.Dispose(); } catch { }
        }

        private HttpResponse Connect()
        {
            HttpResponse? response = null;
            var error = true;
            try
            {
                if (this.callback == null || this.pieceId == null) throw new OperationCanceledException();
                var piece = this.callback.GetPiece(this.pieceId);
                var firstRequest = this.callback.IsFirstRequest(piece.StreamType);

                if (piece.Length == -1 && !this.callback.IsFirstRequest(piece.StreamType))
                    throw new NonRetriableException(ErrorCode.NonResumable, "Resume not supported");

                var hc = this.callback.GetSharedHttpClient(this.pieceId);
                if (hc == null) throw new OperationCanceledException();
                request = CreateRequest(hc, piece);
                response = hc.Send(request);
                CancellationToken.ThrowIfCancellationRequested();
                response.EnsureSuccessStatusCode();

                if (firstRequest && response.ContentType == "text/plain" && this.callback.IsTextRedirectionAllowed())
                {
                    throw new TextRedirectException(new Uri(response.ReadAsString(CancellationToken).Trim()));
                }
                if (!firstRequest && response.StatusCode != HttpStatusCode.PartialContent)
                {
                    throw new NonRetriableException(ErrorCode.InvalidResponse, "Resume not supported :: " + piece.Id);
                }
                var contentLength = response?.ContentLength ?? 0;
                if (!firstRequest && response!.ContentRangeLength > 0 && this.callback.IsFileChangedOnServer(piece.StreamType, response!.ContentRangeLength, null))
                {
                    throw new NonRetriableException(ErrorCode.InvalidResponse, "Content length mismatch :: " + piece.Id);
                }
                maxByteRange = contentLength <= 0 ? -1 : piece.Offset + contentLength;
                actualHttpResponseSize = maxByteRange;
                this.callback?.PieceConnected(this.pieceId, firstRequest ? CreateProbeResult(response!) : null);
                error = false;
                return response!;
            }
            finally
            {
                if (error)
                {
                    try { request?.Abort(); } catch { }
                    try { response?.Close(); } catch { }
                }
            }
        }

        private void Download(HttpResponse response)
        {
            if (this.callback == null || this.pieceId == null) return;
            var piece = this.callback.GetPiece(this.pieceId);
            using var sourceStream = response.GetResponseStream();// response.Content.ReadAsStreamAsync().ConfigureAwait(false);
            CancellationToken.ThrowIfCancellationRequested();
            //#if NET5_0_OR_GREATER
            //            await using var sourceStream = await response.Content.ReadAsStreamAsync(CancellationToken).ConfigureAwait(false);
            //#else
            //            using var sourceStream = await response.Content.ReadAsStreamAsync().ConfigureAwait(false);
            //            CancellationToken.ThrowIfCancellationRequested();
            //#endif
            try
            {
                using var targetStream = new FileStream(this.callback.GetPieceFile(this.pieceId), FileMode.OpenOrCreate, FileAccess.Write);
                this.fileWriterStream = targetStream;
                targetStream.Seek(piece.Downloaded, SeekOrigin.Begin);
                if (piece.Length > 0)
                {
                    CopyWithFixedLength(piece, sourceStream, targetStream);
                }
                else
                {
                    CopyWithUnknownLength(piece, sourceStream, targetStream);
                }
            }
            finally
            {
                this.fileWriterStream = null;
            }
        }

        private void CopyWithFixedLength(Piece piece, Stream sourceStream, Stream targetStream)
        {
#if NET35
            var BUF = new byte[32 * 1024];
#else
            var BUF = System.Buffers.ArrayPool<byte>.Shared.Rent(32 * 1024);
#endif
            try
            {
                var count = 0;
                while (!CancellationToken.IsCancellationRequested)
                {
                    if (this.pieceId == null || this.callback == null) break;
                    var remaining = piece.Length - piece.Downloaded;
                    if (remaining <= 0)
                    {
                        if (maxByteRange > 0 && this.callback.ContinueAdjacentPiece(this.pieceId, maxByteRange))
                        {
                            piece = this.callback.GetPiece(this.pieceId);
                            remaining = piece.Length - piece.Downloaded;
                        }
                        else
                        {
                            CloseUnfinishedRequest(count);
                            break;
                        }
                    }
                    var x = sourceStream.Read(BUF, 0,
                        (int)Math.Min(BUF.Length, remaining));
                    this.CancellationToken.ThrowIfCancellationRequested();
                    if (x == 0)
                    {
                        throw new DownloadException(ErrorCode.Generic, "Unexpected EOF :: " + piece.Id);
                    }
                    try
                    {
                        targetStream.Write(BUF, 0, x);
                        count += x;
                    }
                    catch (IOException ioe)
                    {
                        Log.Debug(ioe, "Disk error");
                        throw new NonRetriableException(ErrorCode.DiskError, "Disk error :: " + piece.Id, ioe);
                    }
                    if (this.CancellationToken.IsCancellationRequested) return;
                    this.callback?.UpdateDownloadedBytesCount(this.pieceId, x);
                    this.callback?.ThrottleIfNeeded();
                }
            }
            finally
            {
#if !NET35
                System.Buffers.ArrayPool<byte>.Shared.Return(BUF, false);
#endif
            }
        }

        private void CloseUnfinishedRequest(long count)
        {
            if (actualHttpResponseSize - count != 0)
            {
                try
                {
                    Log.Debug("Disable connection reuse");
                    this.request?.Abort();
                }
                catch { }//Dont allow to reuse if entire content has not been read
            }
        }

        private void CopyWithUnknownLength(Piece piece, Stream sourceStream, Stream targetStream)
        {
#if NET35
            var BUF = new byte[32 * 1024];
#else
            var BUF = System.Buffers.ArrayPool<byte>.Shared.Rent(32 * 1024);
#endif

            try
            {
                while (!CancellationToken.IsCancellationRequested)
                {
                    if (this.pieceId == null || this.callback == null) break;
                    var x = sourceStream.Read(BUF, 0, BUF.Length);
                    this.CancellationToken.ThrowIfCancellationRequested();
                    if (x == 0)
                    {
                        break;
                    }
                    try
                    {
                        targetStream.Write(BUF, 0, x);
                    }
                    catch (IOException ioe)
                    {
                        Log.Debug(ioe, "Disk error");
                        throw new NonRetriableException(ErrorCode.DiskError, "Disk error :: " + piece.Id, ioe);
                    }
                    if (this.CancellationToken.IsCancellationRequested) return;
                    this.callback?.UpdateDownloadedBytesCount(this.pieceId, x);
                    this.callback?.ThrottleIfNeeded();
                }
            }
            finally
            {
#if !NET35
                System.Buffers.ArrayPool<byte>.Shared.Return(BUF, false);
#endif
            }
        }

        private HttpRequest CreateRequest(IHttpClient hc, Piece piece)
        {
            if (this.callback == null || this.pieceId == null) throw new OperationCanceledException();
            var headerCookieUrl = this.callback.GetHeaderUrlAndCookies(this.pieceId);
            if (headerCookieUrl == null) throw new OperationCanceledException();
            if (headerCookieUrl.Value.Headers != null)
            {
                foreach (var item in headerCookieUrl.Value.Headers.Keys)
                {
                    Log.Debug(item + ": " + headerCookieUrl.Value.Headers[item].First());
                }
            }
            Log.Debug("Create request with " + headerCookieUrl.Value.Headers?.Count +
                " headers and " +
                headerCookieUrl.Value.Cookies?.Count + " cookies");
            var req = hc.CreateGetRequest(this.redirectUri ?? headerCookieUrl.Value.Url,
                headerCookieUrl.Value.Headers,
                headerCookieUrl.Value.Cookies,
                headerCookieUrl.Value.Authentication);
            if (this.callback.IsFirstRequest(piece.StreamType))
            {
                req.AddRange(0);
            }
            else
            {
                Log.Debug("Range: " + (piece.Offset + piece.Downloaded) + "-" + (piece.Offset + piece.Length - 1));
                req.AddRange(piece.Offset + piece.Downloaded, piece.Offset + piece.Length - 1);
            }
            return req;
        }

        //private void SetAuthentication(HttpRequestMessage request, string user, string password)
        //{
        //    try
        //    {
        //        request.Headers.Authorization = new AuthenticationHeaderValue("Basic",
        //            Convert.ToBase64String(Encoding.UTF8.GetBytes(user + ":" + password)));
        //    }
        //    catch (Exception ex)
        //    {
        //        Log.Debug(ex, "Error setting auth header value: " + this.pieceId);
        //    }
        //}

        //private void SetHeader(HttpRequestMessage request, string key, IEnumerable<string> value)
        //{
        //    try
        //    {
        //        request.Headers.Add(key, value);
        //    }
        //    catch (Exception ex)
        //    {
        //        Log.Debug(ex, "Error setting header value: " + this.pieceId);
        //    }
        //}

        //private void SetHeader(HttpRequestMessage request, string key, string value)
        //{
        //    try
        //    {
        //        request.Headers.Add(key, value);
        //    }
        //    catch (Exception ex)
        //    {
        //        Log.Debug(ex, "Error setting header value: " + this.pieceId);
        //    }
        //}

        private ProbeResult CreateProbeResult(HttpResponse response)
        {
            return new ProbeResult
            {
                ResourceSize = response.ContentLength,
                Resumable = response.StatusCode == HttpStatusCode.PartialContent,
                FinalUri = redirectUri ?? response.ResponseUri,
                AttachmentName = response.ContentDispositionFileName,
                ContentType = response.ContentType,
                LastModified = response.LastModified
            };
        }

        private void sleep(int interval)
        {
            sleepHandle.WaitOne(interval);
        }

        //private HttpWebResponse SendRequest(HttpWebRequest request)
        //{
        //    HttpWebResponse response;
        //    try
        //    {
        //        response = (HttpWebResponse)request.GetResponse();
        //    }
        //    catch (WebException we)
        //    {
        //        if (we.Response == null)
        //        {
        //            throw new Exception("Connectivity error");
        //        }
        //        response = (HttpWebResponse?)we.Response!;
        //    }
        //    return response;
        //}
    }
}
