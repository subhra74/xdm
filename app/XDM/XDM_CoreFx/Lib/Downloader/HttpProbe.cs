//using System;
//using System.Net;
//using System.Net.Http;
//using System.Net.Http.Headers;
//using System.Threading;

//using System.Collections.Generic;
//using XDM.Core.Lib.Common;

//namespace XDM.Core.Lib.Downloader
//{
//    public class HttpProbe
//    {
//        const int MaxRetry = 3;
//        public async Task<ProbeResult> ProbeAsync(Uri uri, System.Net.Http.HttpClient http, CancellationToken cancellationToken,
//            Dictionary<string, List<string>> headers = null)
//        {
//            for (int i = 0; i < MaxRetry; i++)
//            {
//                if (cancellationToken.IsCancellationRequested)
//                {
//                    break;
//                }
//                try
//                {
//                    var request = new HttpRequestMessage(HttpMethod.Get, uri);
//#if NET5_0
//                    request.Version = HttpVersion.Version20;
//#endif
//                    request.Headers.Range = new RangeHeaderValue(0, 512);
//                    if (headers != null)
//                    {
//                        foreach (var header in headers)
//                        {
//                            request.Headers.Add(header.Key, header.Value);
//                        }
//                    }
//                    var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);

//                    Console.WriteLine("Response version: " + response.Version);

//                    if (response.StatusCode == HttpStatusCode.OK)
//                    {
//                        return new ProbeResult
//                        {
//                            Resumable = false,
//                            ResourceSize = response.Content.Headers.ContentLength ?? -1,
//                            Response = response,
//                            FinalUri = response.RequestMessage.RequestUri,
//                            AttachmentName = response.Content.Headers.ContentDisposition?.FileName?.Trim('\"'),
//                            ContentType = response.Content.Headers.ContentType?.MediaType
//                        };
//                    }

//                    if (response.StatusCode != HttpStatusCode.PartialContent)
//                    {
//                        throw new Exception($"Invalid response code: {response.StatusCode}",
//                            new HttpException(response.ReasonPhrase, null, response.StatusCode));
//                    }

//                    //drop 10 byte payload for connection reuse
//                    await response.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
//                    response.Dispose();
//                    return new ProbeResult
//                    {
//                        ResourceSize = response.Content.Headers.ContentRange?.Length ?? response.Content.Headers.ContentLength,
//                        Resumable = response.Content.Headers.ContentRange?.HasLength ?? false,
//                        FinalUri = response.RequestMessage.RequestUri,
//                        AttachmentName = response.Content.Headers.ContentDisposition?.FileName?.Trim('\"'),
//                        ContentType = response.Content.Headers.ContentType?.MediaType
//                    };
//                }
//                catch (HttpException)
//                {
//                    try
//                    {
//                        await Task.Delay(1000, cancellationToken);
//                    }
//                    catch { }
//                }
//            }

//            throw new HttpException();
//        }
//    }

//    public class ProbeResult
//    {
//        public bool Resumable { get; set; }
//        public long? ResourceSize { get; set; }
//        public HttpResponseMessage? Response { get; set; }
//        public Uri? FinalUri { get; set; }
//        public string? AttachmentName { get; set; }
//        public string? ContentType { get; set; }
//        public DateTime LastModified { get; set; }
//    }
//}
