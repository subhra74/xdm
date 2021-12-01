using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;
using NUnit.Framework;
using XDM.Core.Lib.Common;
//using XDM.Core.Lib.Downloader.YT.Dash;
using XDM.Core.Lib.Common.Segmented;
using XDM.Core.Lib.Common.Segmented;
using Moq;
using System.Net.Http;
using Moq.Protected;
using System.Threading;
using System.Collections.Generic;

using static XDM.SystemTests.TestUtil;
using XDM.Core.Lib.Util;
using System.Text.RegularExpressions;
using Serilog;

namespace XDM.SystemTests
{
    public class HttpSanityTests
    {
        [SetUp]
        public void Setup()
        {
            Config.DataDir = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
            Directory.CreateDirectory(Config.DataDir);

            Log.Logger = new LoggerConfiguration()
                .MinimumLevel.Debug()
                .WriteTo.Console()
                .CreateLogger();
        }

        //[TestCase("xyz.zip", true)]
        //[TestCase("xyz.zip", false)]
        //public async Task ProbeTargetAsyncSuccess(string name, bool useName)
        //{
        //    var response = new HttpResponseMessage { StatusCode = System.Net.HttpStatusCode.PartialContent };
        //    response.Content = new ByteArrayContent(new byte[512]);
        //    response.Content.Headers.ContentRange = new System.Net.Http.Headers.ContentRangeHeaderValue(512);
        //    response.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/zip");

        //    var handlerMock = new Mock<HttpMessageHandler>();
        //    handlerMock
        //       .Protected()
        //       .Setup<Task<HttpResponseMessage>>(
        //          "SendAsync",
        //          ItExpr.IsAny<HttpRequestMessage>(),
        //          ItExpr.IsAny<CancellationToken>())
        //       .ReturnsAsync(response);

        //    var httpClient = new HttpClient(handlerMock.Object);

        //    string id = Guid.NewGuid().ToString();
        //    string tempDir = Path.Combine(Path.GetTempPath(), id);
        //    Directory.CreateDirectory(tempDir);
        //    var hc = new HttpDownloader(new SingleSourceHTTPDownloadInfo { Uri = $"http://some/dummy/{name}" }, httpClient);
        //    hc.SetTargetDirectory(tempDir);
        //    if (useName)
        //    {
        //        hc.SetFileName(name);
        //    }
        //    await hc.ProbeTargetAsync().ConfigureAwait(false);
        //    Assert.AreEqual(hc.TargetFileName, name);
        //}

        //[Test]
        //public async Task DownloadAsyncNonResumableSuccess()
        //{
        //    string mockId = Guid.NewGuid().ToString();
        //    MockServer.MockServer mockServer = new MockServer.MockServer();
        //    mockServer.AddMockHandler(mockId);
        //    mockServer.NonResumable = true;
        //    mockServer.StartAsync();

        //    string id = Guid.NewGuid().ToString();
        //    string tempDir = Path.Combine(Path.GetTempPath(), id);
        //    Directory.CreateDirectory(tempDir);
        //    var hc = new HttpDownloader(new SingleSourceHTTPDownloadInfo { Uri = $"http://127.0.0.1:39000/{mockId}" });
        //    hc.SetTargetDirectory(tempDir);
        //    await hc.DownloadAsync().ConfigureAwait(false);

        //    mockServer.Stop();

        //    Assert.NotZero(hc.FileSize);
        //    string hash = mockServer.GetHash(mockId);
        //    Assert.AreEqual(hash, GetFileHash(hc.TargetFile));
        //    Assert.DoesNotThrow(() => File.Delete(hc.TargetFile));
        //}

        //[Test]
        //public async Task DownloadAsyncAttachmentSuccess()
        //{
        //    string mockId = Guid.NewGuid().ToString();
        //    var name = "filename.zip";
        //    MockServer.MockServer mockServer = new MockServer.MockServer();
        //    var headers = new Dictionary<string, string>
        //    {
        //        ["Content-Disposition"] = $"attachment; filename=\"{name}\"",
        //        ["Content-Type"] = "application/zip"
        //    };
        //    mockServer.AddMockHandler(mockId, headers: headers);
        //    mockServer.NonResumable = true;
        //    mockServer.StartAsync();

        //    string id = Guid.NewGuid().ToString();
        //    string tempDir = Path.Combine(Path.GetTempPath(), id);
        //    Directory.CreateDirectory(tempDir);
        //    var hc = new HttpDownloader(new SingleSourceHTTPDownloadInfo { Uri = $"http://127.0.0.1:39000/{mockId}" });
        //    hc.SetTargetDirectory(tempDir);
        //    await hc.DownloadAsync().ConfigureAwait(false);

        //    mockServer.Stop();

        //    Assert.NotZero(hc.FileSize);
        //    string hash = mockServer.GetHash(mockId);
        //    Assert.AreEqual(hash, GetFileHash(hc.TargetFile));
        //    Assert.AreEqual(Path.GetFileName(hc.TargetFile), name);
        //    Assert.DoesNotThrow(() => File.Delete(hc.TargetFile));
        //}

        //[Test]
        //public async Task DownloadAsyncOnDropoutFails()
        //{
        //    string mockId = Guid.NewGuid().ToString();

        //    MockServer.MockServer mockServer = new MockServer.MockServer();
        //    mockServer.AddMockHandler(mockId);
        //    mockServer.StartAsync();

        //    string id = Guid.NewGuid().ToString();

        //    string tempDir = Path.Combine(Path.GetTempPath(), id);
        //    Directory.CreateDirectory(tempDir);

        //    var hc = new HttpDownloader(new SingleSourceHTTPDownloadInfo { Uri = $"http://127.0.0.1:39000/{mockId}" });
        //    hc.SetTargetDirectory(tempDir);
        //    Task downloadTask = hc.DownloadAsync();
        //    await Task.WhenAny(downloadTask, Task.Delay(2000));

        //    mockServer.Stop();

        //    await Task.WhenAll(downloadTask);

        //    Assert.IsTrue(hc.IsCancelled);

        //    Assert.DoesNotThrow(() => File.Delete(hc.TargetFile));
        //}

        //[Test]
        //public async Task DownloadAsyncOnChunkRestoreFailSuccess()
        //{
        //    string mockId = Guid.NewGuid().ToString();

        //    MockServer.MockServer mockServer = new MockServer.MockServer();
        //    mockServer.AddMockHandler(mockId);
        //    mockServer.StartAsync();

        //    string id = Guid.NewGuid().ToString();

        //    string tempDir = Path.Combine(Path.GetTempPath(), id);
        //    Directory.CreateDirectory(tempDir);

        //    var hc = new HttpDownloader(new SingleSourceHTTPDownloadInfo { Uri = $"http://127.0.0.1:39000/{mockId}" });
        //    hc.SetTargetDirectory(tempDir);
        //    Task downloadTask = hc.DownloadAsync();
        //    await Task.WhenAny(downloadTask, Task.Delay(3000));

        //    hc.StopDownload();

        //    string downloadId = hc.Id;

        //    await Task.WhenAll(downloadTask);

        //    Console.WriteLine(hc.FileSize);
        //    Assert.NotZero(hc.FileSize);

        //    //delete chunk list intentionally to test download starts from begining
        //    File.Delete(Path.Combine(Path.GetTempPath(), downloadId + ".chunks"));

        //    hc = new HttpDownloader(downloadId);
        //    await hc.ResumeAsync();

        //    mockServer.Stop();
        //    string hash = mockServer.GetHash(mockId);
        //    Assert.AreEqual(hash, GetFileHash(hc.TargetFile));
        //}

        //[Test]
        //public async Task DownloadAsyncPauseResumeSuccess()
        //{
        //    string mockId = Guid.NewGuid().ToString();

        //    MockServer.MockServer mockServer = new MockServer.MockServer();
        //    mockServer.AddMockHandler(mockId);
        //    mockServer.StartAsync();

        //    string id = Guid.NewGuid().ToString();

        //    string tempDir = Path.Combine(Path.GetTempPath(), id);
        //    Directory.CreateDirectory(tempDir);

        //    var hc = new HttpDownloader(new SingleSourceHTTPDownloadInfo { Uri = $"http://127.0.0.1:39000/{mockId}" });
        //    hc.SetTargetDirectory(tempDir);
        //    Task downloadTask = hc.DownloadAsync();
        //    await Task.WhenAny(downloadTask, Task.Delay(3000));

        //    hc.StopDownload();

        //    string downloadId = hc.Id;

        //    await Task.WhenAll(downloadTask);

        //    Console.WriteLine(hc.FileSize);
        //    Assert.NotZero(hc.FileSize);

        //    hc = new HttpDownloader(downloadId);
        //    await hc.ResumeAsync();

        //    mockServer.Stop();
        //    string hash = mockServer.GetHash(mockId);
        //    Assert.AreEqual(hash, GetFileHash(hc.TargetFile));
        //}

        //[Test]
        //public void DownloadAsyncDownloadSuccess()
        //{
        //    string mockId = Guid.NewGuid().ToString();

        //    MockServer.MockServer mockServer = new MockServer.MockServer();
        //    mockServer.AddMockHandler(mockId);
        //    mockServer.StartAsync();

        //    string id = Guid.NewGuid().ToString();

        //    string tempDir = Path.Combine(Path.GetTempPath(), id);
        //    Directory.CreateDirectory(tempDir);

        //    var hc = new HttpDownloader(new SingleSourceHTTPDownloadInfo { Uri = $"http://127.0.0.1:39000/{mockId}" });
        //    hc.SetTargetDirectory(tempDir);

        //    hc.DownloadAsync().Wait();

        //    Console.WriteLine(hc.FileSize);
        //    Assert.NotZero(hc.FileSize);

        //    mockServer.Stop();
        //    string hash = mockServer.GetHash(mockId);
        //    Assert.AreEqual(hash, GetFileHash(hc.TargetFile));
        //}

        //[Test]
        //public void YtDashDownloadAsyncDownloadSuccess()
        //{
        //    var mockId1 = Guid.NewGuid().ToString();
        //    var mockId2 = Guid.NewGuid().ToString();

        //    MockServer.MockServer mockServer = new MockServer.MockServer();
        //    mockServer.AddMockHandler(mockId1);
        //    mockServer.AddMockHandler(mockId2);
        //    mockServer.StartAsync();

        //    string id = Guid.NewGuid().ToString();

        //    string tempDir = Path.Combine(Path.GetTempPath(), id);
        //    Directory.CreateDirectory(tempDir);

        //    var hc = new YTDashDownloader(new DualSourceHTTPDownloadInfo
        //    {
        //        Uri1 = $"http://127.0.0.1:39000/{mockId1}",
        //        Uri2 = $"http://127.0.0.1:39000/{mockId2}"
        //    });
        //    hc.SetTargetDirectory(tempDir);

        //    hc.DownloadAsync().Wait();

        //    Console.WriteLine(hc.FileSize);
        //    Assert.NotZero(hc.FileSize);

        //    mockServer.Stop();
        //    string hash1 = mockServer.GetHash(mockId1);
        //    string hash2 = mockServer.GetHash(mockId2);
        //    Assert.AreEqual(hash1, GetFileHash(hc.TargetFile + ".part1"));
        //    Assert.AreEqual(hash2, GetFileHash(hc.TargetFile + ".part2"));
        //}

        //[Test]
        //public async Task YtDashDownloadAsyncPauseResumeSuccess()
        //{
        //    var mockId1 = Guid.NewGuid().ToString();
        //    var mockId2 = Guid.NewGuid().ToString();

        //    MockServer.MockServer mockServer = new MockServer.MockServer();
        //    mockServer.AddMockHandler(mockId1);
        //    mockServer.AddMockHandler(mockId2);

        //    mockServer.StartAsync();

        //    string id = Guid.NewGuid().ToString();

        //    string tempDir = Path.Combine(Path.GetTempPath(), id);
        //    Directory.CreateDirectory(tempDir);

        //    var hc = new YTDashDownloader(new DualSourceHTTPDownloadInfo
        //    {
        //        Uri1 = $"http://127.0.0.1:39000/{mockId1}",
        //        Uri2 = $"http://127.0.0.1:39000/{mockId2}"
        //    });
        //    hc.SetTargetDirectory(tempDir);
        //    Task downloadTask = hc.DownloadAsync();
        //    await Task.WhenAny(downloadTask, Task.Delay(3000));

        //    hc.StopDownload();

        //    string downloadId = hc.Id;

        //    await Task.WhenAll(downloadTask);

        //    Console.WriteLine(hc.FileSize);
        //    Assert.NotZero(hc.FileSize);

        //    hc = new YTDashDownloader(downloadId);
        //    await hc.ResumeAsync();

        //    mockServer.Stop();
        //    string hash1 = mockServer.GetHash(mockId1);
        //    string hash2 = mockServer.GetHash(mockId2);
        //    Assert.AreEqual(hash1, GetFileHash(hc.TargetFile + ".part1"));
        //    Assert.AreEqual(hash2, GetFileHash(hc.TargetFile + ".part2"));
        //}

        //[Ignore("Execute for special case")]
        //[TestCase("https://github.com/subhra74/xdm/releases/download/7.2.11/xdman.jar")]
        //[TestCase("https://ffmpeg.org/releases/ffmpeg-snapshot.tar.bz2")]
        //[TestCase("https://github.com/subhra74/snowflake/releases/download/v1.0.4/snowflake-1.0.4-setup-amd64.bin")]

        ////[TestCase("http://mirrors.evowise.com/linuxmint/stable/20/linuxmint-20-cinnamon-64bit.iso")]
        //public void DownloadAsyncRealUrlSuccess(string url)
        //{
        //    string configDir = Path.GetTempPath();
        //    string id = Guid.NewGuid().ToString();

        //    string tempDir = @"C:\Users\subhrad\Documents\IISExpress";//Path.Combine(Path.GetTempPath(), id);
        //    Directory.CreateDirectory(tempDir);

        //    Console.WriteLine(tempDir);

        //    var hc = new HttpDownloader(new SingleSourceHTTPDownloadInfo { Uri = url });
        //    hc.SetTargetDirectory(tempDir);
        //    hc.DownloadAsync().Wait();

        //    Console.WriteLine(hc.FileSize);
        //    Assert.NotZero(hc.FileSize);
        //}

        //[TestCase("https://github.com/subhra74/xdm/releases/download/7.2.11/xdman.jar")]
        //public async Task DownloadSegmented(string url)
        //{
        //    string configDir = Path.GetTempPath();
        //    string id = Guid.NewGuid().ToString();

        //    string tempDir = @"C:\Users\subhro\Documents\IISExpress\out";//Path.Combine(Path.GetTempPath(), id);
        //    Directory.CreateDirectory(tempDir);

        //    Console.WriteLine(tempDir);

        //    CancellationTokenSource cs = new CancellationTokenSource();

        //    var hc = new SegmentedDownloader(new Uri(url), configDir, tempDir, null, res => cs.Cancel());
        //    hc.Start();
        //    await Task.Delay(30000, cs.Token);

        //    //Assert.NotZero(hc.Id);
        //}

        [Test]
        [MaxTime(120000)]
        public async Task Start_WithMock_Success()
        {
            string mockId = Guid.NewGuid().ToString();

            using MockServer.MockServer mockServer = new MockServer.MockServer();
            mockServer.AddMockHandler(mockId);
            mockServer.StartAsync();

            string id = Guid.NewGuid().ToString();

            string outDir = Path.Combine(Path.GetTempPath(), id);
            Directory.CreateDirectory(outDir);

            var success = false;

            var cs = new CancellationTokenSource();

            var hc = new SingleSourceHTTPDownloader(new SingleSourceHTTPDownloadInfo { Uri = $"http://127.0.0.1:39000/{mockId}" });
            hc.Finished += (a, b) =>
            {
                success = true;
                cs.Cancel();
            };
            hc.Failed += (a, b) =>
            {
                success = false;
                cs.Cancel();
            };
            hc.TargetDir = outDir;
            hc.Start();
            try
            {
                await Task.Delay(Int32.MaxValue, cs.Token);
            }
            catch { }

            Assert.IsTrue(success);

            string hash1 = mockServer.GetHash(mockId);
            Assert.AreEqual(hash1, GetFileHash(hc.TargetFile));

        }

        [Test]
        public async Task Start_WithMock_PauseResume_Success()
        {
            string mockId = Guid.NewGuid().ToString();

            using MockServer.MockServer mockServer = new MockServer.MockServer();
            mockServer.AddMockHandler(mockId);
            mockServer.StartAsync();

            string id = Guid.NewGuid().ToString();

            string outDir = Path.Combine(Path.GetTempPath(), id);
            Directory.CreateDirectory(outDir);

            var success = false;

            var cs = new CancellationTokenSource();
            var hc = new SingleSourceHTTPDownloader(new SingleSourceHTTPDownloadInfo { Uri = $"http://127.0.0.1:39000/{mockId}" });

            hc.TargetDir = outDir;
            hc.Start();

            await Task.Delay(9000);

            hc.Stop();

            await Task.Delay(2000);
            var name = hc.TargetFileName;

            hc = new SingleSourceHTTPDownloader(hc.Id);
            hc.TargetDir = outDir;
            hc.TargetFileName = name;
            hc.Finished += (a, b) =>
            {
                success = true;
                cs.Cancel();
            };
            hc.Failed += (a, b) =>
            {
                success = false;
                cs.Cancel();
            };
            hc.Resume();

            try
            {
                await Task.Delay(Int32.MaxValue, cs.Token);
            }
            catch { }

            Assert.IsTrue(success);

            string hash1 = mockServer.GetHash(mockId);
            Assert.AreEqual(hash1, GetFileHash(hc.TargetFile));
        }

        [Test]
        public async Task Start_WithMock_NonResumable_Success()
        {
            string mockId = Guid.NewGuid().ToString();

            using MockServer.MockServer mockServer = new MockServer.MockServer();
            mockServer.AddMockHandler(mockId, start: 10, end: 20);
            mockServer.NonResumable = true;
            mockServer.StartAsync();

            string id = Guid.NewGuid().ToString();

            string outDir = Path.Combine(Path.GetTempPath(), id);
            Directory.CreateDirectory(outDir);

            var success = false;

            var cs = new CancellationTokenSource();

            var hc = new SingleSourceHTTPDownloader(new SingleSourceHTTPDownloadInfo { Uri = $"http://127.0.0.1:39000/{mockId}" });
            hc.Finished += (a, b) =>
            {
                success = true;
                cs.Cancel();
            };
            hc.Failed += (a, b) =>
            {
                success = false;
                cs.Cancel();
            };
            hc.TargetDir = outDir;
            hc.Start();
            try
            {
                await Task.Delay(Int32.MaxValue, cs.Token);
            }
            catch { }

            Assert.IsTrue(success);
            Assert.IsTrue(hc.FileSize > 0);
            var hash1 = mockServer.GetHash(mockId);
            Assert.AreEqual(hash1, GetFileHash(hc.TargetFile));

        }

        [Test]
        [Timeout(120000)]
        public async Task Start_WithMock_NonResumableNoContentLength_Success()
        {
            string mockId = Guid.NewGuid().ToString();

            using MockServer.MockServer mockServer = new MockServer.MockServer();
            var ret = mockServer.AddMockHandler(mockId, start: 10, end: 20);
            mockServer.NonResumable = true;
            mockServer.HasContentLength = false;
            mockServer.StartAsync();

            Console.WriteLine("Actual file size: {0}", ret.Size);

            string id = Guid.NewGuid().ToString();

            string outDir = Path.Combine(Path.GetTempPath(), id);
            Directory.CreateDirectory(outDir);

            var success = false;

            var cs = new CancellationTokenSource();

            var hc = new SingleSourceHTTPDownloader(new SingleSourceHTTPDownloadInfo { Uri = $"http://127.0.0.1:39000/{mockId}" });
            hc.Finished += (a, b) =>
            {
                success = true;
                cs.Cancel();
            };
            hc.Failed += (a, b) =>
            {
                success = false;
                cs.Cancel();
            };
            hc.TargetDir = outDir;
            Console.WriteLine("Out path: {0}", Path.Combine(Config.DataDir, hc.Id));

            hc.Start();
            try
            {
                await Task.Delay(Int32.MaxValue, cs.Token);
            }
            catch { }

            Assert.IsTrue(success);
            Assert.IsTrue(hc.FileSize > 0);
            Assert.AreEqual(hc.FileSize, ret.Size);
            var hash1 = mockServer.GetHash(mockId);
            Assert.AreEqual(hash1, GetFileHash(hc.TargetFile));
        }

        [Test]
        public async Task Start_OnDropout_Fails()
        {
            string mockId = Guid.NewGuid().ToString();

            using MockServer.MockServer mockServer = new MockServer.MockServer();
            mockServer.AddMockHandler(mockId);
            mockServer.StartAsync();

            string id = Guid.NewGuid().ToString();

            string outDir = Path.Combine(Path.GetTempPath(), id);
            Directory.CreateDirectory(outDir);

            var success = true;

            var cs = new CancellationTokenSource();

            var hc = new SingleSourceHTTPDownloader(new SingleSourceHTTPDownloadInfo { Uri = $"http://127.0.0.1:39000/{mockId}" });
            hc.Finished += (a, b) =>
            {
                success = true;
                cs.Cancel();
            };
            hc.Failed += (a, b) =>
            {
                Console.WriteLine("Test: download failed");
                success = false;
                cs.Cancel();
            };
            hc.TargetDir = outDir;
            hc.Start();
            try
            {
                await Task.Delay(3000, cs.Token);
            }
            catch { }
            mockServer.Stop();

            try
            {
                await Task.Delay(Int32.MaxValue, cs.Token);
            }
            catch { }

            Assert.IsFalse(success);
            Assert.IsFalse(hc.IsCancelled);
            var path = Path.Combine(Config.DataDir, hc.Id);
            Assert.DoesNotThrow(() => Directory.Delete(path, true));
        }

        [Test]
        public async Task Start_OnChunkRestoreFail_Success()
        {
            string mockId = Guid.NewGuid().ToString();

            using MockServer.MockServer mockServer = new MockServer.MockServer();
            mockServer.AddMockHandler(mockId);
            mockServer.StartAsync();

            string id = Guid.NewGuid().ToString();

            string outDir = Path.Combine(Path.GetTempPath(), id);
            Directory.CreateDirectory(outDir);

            var success = false;

            var cs = new CancellationTokenSource();
            var hc = new SingleSourceHTTPDownloader(new SingleSourceHTTPDownloadInfo { Uri = $"http://127.0.0.1:39000/{mockId}" });

            hc.TargetDir = outDir;
            hc.Start();

            await Task.Delay(6000);

            hc.Stop();

            await Task.Delay(2000);
            var name = hc.TargetFileName;

            Directory.Delete(Path.Combine(Config.DataDir, hc.Id), true);

            hc = new SingleSourceHTTPDownloader(hc.Id);
            hc.TargetDir = outDir;
            hc.TargetFileName = name;
            hc.Finished += (a, b) =>
            {
                success = true;
                cs.Cancel();
            };
            hc.Failed += (a, b) =>
            {
                success = false;
                cs.Cancel();
            };
            hc.Resume();

            try
            {
                await Task.Delay(Int32.MaxValue, cs.Token);
            }
            catch { }

            Assert.IsTrue(success);

            string hash1 = mockServer.GetHash(mockId);
            Assert.AreEqual(hash1, GetFileHash(hc.TargetFile));
        }

        [Test]
        public async Task Start_WithMockAndAttachment_Success()
        {
            var name = "filename.zip";
            var headers = new Dictionary<string, string>
            {
                ["Content-Disposition"] = $"attachment; filename=\"{name}\"",
                ["Content-Type"] = "application/zip"
            };

            var mockId = Guid.NewGuid().ToString();
            using MockServer.MockServer mockServer = new MockServer.MockServer();
            mockServer.AddMockHandler(mockId, headers: headers);
            mockServer.StartAsync();

            string id = Guid.NewGuid().ToString();

            string outDir = Path.Combine(Path.GetTempPath(), id);
            Directory.CreateDirectory(outDir);

            var success = false;

            var cs = new CancellationTokenSource();

            var hc = new SingleSourceHTTPDownloader(new SingleSourceHTTPDownloadInfo { Uri = $"http://127.0.0.1:39000/{mockId}" });
            hc.Finished += (a, b) =>
            {
                success = true;
                cs.Cancel();
            };
            hc.Failed += (a, b) =>
            {
                success = false;
                cs.Cancel();
            };
            hc.TargetDir = outDir;
            hc.Start();
            try
            {
                await Task.Delay(Int32.MaxValue, cs.Token);
            }
            catch { }

            Assert.IsTrue(success);

            string hash1 = mockServer.GetHash(mockId);
            Assert.AreEqual(GetFileHash(hc.TargetFile), hash1);
            Assert.AreEqual(hc.TargetFileName, name);
        }

        [Test]
        [Timeout(120000)]
        public async Task Start_WithMock_DualSource_Success()
        {
            var mockId1 = Guid.NewGuid().ToString();
            var mockId2 = Guid.NewGuid().ToString();

            using MockServer.MockServer mockServer = new MockServer.MockServer();
            mockServer.AddMockHandler(mockId1, start: 20, end: 30);
            mockServer.AddMockHandler(mockId2);
            mockServer.StartAsync();

            string id = Guid.NewGuid().ToString();

            string outDir = Path.Combine(Path.GetTempPath(), id);
            Directory.CreateDirectory(outDir);

            var success = false;

            var cs = new CancellationTokenSource();

            var hc = new DualSourceHTTPDownloader(
                new DualSourceHTTPDownloadInfo
                {
                    Uri1 = $"http://127.0.0.1:39000/{mockId1}",
                    Uri2 = $"http://127.0.0.1:39000/{mockId2}"
                },
                mediaProcessor: new FakeMediaProcessor());
            hc.Finished += (a, b) =>
            {
                success = true;
                cs.Cancel();
            };
            hc.Failed += (a, b) =>
            {
                success = false;
                cs.Cancel();
            };
            hc.TargetDir = outDir;
            hc.Start();
            try
            {
                await Task.Delay(Int32.MaxValue, cs.Token);
            }
            catch { }

            Assert.IsTrue(success);

            Console.WriteLine(hc.FileSize);
            Assert.NotZero(hc.FileSize);

            //mockServer.Stop();
            string hash1 = mockServer.GetHash(mockId1);
            string hash2 = mockServer.GetHash(mockId2);
            Assert.AreEqual(hash1, GetFileHash(Path.Combine(Config.DataDir, hc.Id, "1_" + hc.Id)));
            Assert.AreEqual(hash2, GetFileHash(Path.Combine(Config.DataDir, hc.Id, "2_" + hc.Id)));
        }

        [Test]
        [Timeout(120000)]
        public async Task Start_WithMock_DualSource_PauseResume_Success()
        {
            var mockId1 = Guid.NewGuid().ToString();
            var mockId2 = Guid.NewGuid().ToString();

            using MockServer.MockServer mockServer = new MockServer.MockServer();
            mockServer.AddMockHandler(mockId1, start: 20, end: 30);
            mockServer.AddMockHandler(mockId2);
            mockServer.StartAsync();

            string id = Guid.NewGuid().ToString();

            string outDir = Path.Combine(Path.GetTempPath(), id);
            Directory.CreateDirectory(outDir);

            var success = false;

            var cs = new CancellationTokenSource();

            var hc = new DualSourceHTTPDownloader(
                new DualSourceHTTPDownloadInfo
                {
                    Uri1 = $"http://127.0.0.1:39000/{mockId1}",
                    Uri2 = $"http://127.0.0.1:39000/{mockId2}"
                }, mediaProcessor: new FakeMediaProcessor());
            hc.Finished += (a, b) =>
            {
                success = true;
                cs.Cancel();
            };
            hc.Failed += (a, b) =>
            {
                success = false;
                cs.Cancel();
            };
            hc.TargetDir = outDir;
            hc.Start();


            await Task.Delay(9000);

            hc.Stop();

            await Task.Delay(2000);
            var name = hc.TargetFileName;

            hc = new DualSourceHTTPDownloader(hc.Id, mediaProcessor: new FakeMediaProcessor());
            hc.TargetDir = outDir;
            hc.TargetFileName = name;
            hc.Finished += (a, b) =>
            {
                success = true;
                cs.Cancel();
            };
            hc.Failed += (a, b) =>
            {
                success = false;
                cs.Cancel();
            };
            hc.Resume();

            try
            {
                await Task.Delay(Int32.MaxValue, cs.Token);
            }
            catch { }

            Assert.IsTrue(success);

            Console.WriteLine(hc.FileSize);
            Assert.NotZero(hc.FileSize);

            string hash1 = mockServer.GetHash(mockId1);
            string hash2 = mockServer.GetHash(mockId2);
            Assert.AreEqual(hash1, GetFileHash(Path.Combine(Config.DataDir, hc.Id, "1_" + hc.Id)));
            Assert.AreEqual(hash2, GetFileHash(Path.Combine(Config.DataDir, hc.Id, "2_" + hc.Id)));

        }

        [Test]
        [Timeout(240000)]
        public async Task Start_WithMock_DualSource_NoContentLengthHeader_Success()
        {
            var mockId1 = Guid.NewGuid().ToString();
            var mockId2 = Guid.NewGuid().ToString();

            using MockServer.MockServer mockServer = new MockServer.MockServer();
            mockServer.AddMockHandler(mockId1, start: 20, end: 30);
            mockServer.AddMockHandler(mockId2, start: 5, end: 10);
            mockServer.HasContentLength = false;
            mockServer.StartAsync();

            string id = Guid.NewGuid().ToString();

            string outDir = Path.Combine(Path.GetTempPath(), id);
            Directory.CreateDirectory(outDir);

            var success = false;

            var cs = new CancellationTokenSource();

            var hc = new DualSourceHTTPDownloader(
                new DualSourceHTTPDownloadInfo
                {
                    Uri1 = $"http://127.0.0.1:39000/{mockId1}",
                    Uri2 = $"http://127.0.0.1:39000/{mockId2}"
                }, mediaProcessor: new FakeMediaProcessor());
            hc.Finished += (a, b) =>
            {
                success = true;
                cs.Cancel();
            };
            hc.Failed += (a, b) =>
            {
                success = false;
                cs.Cancel();
            };
            hc.TargetDir = outDir;
            hc.Start();
            try
            {
                await Task.Delay(Int32.MaxValue, cs.Token);
            }
            catch { }

            Assert.IsTrue(success);

            Console.WriteLine(hc.FileSize);
            Assert.NotZero(hc.FileSize);

            string hash1 = mockServer.GetHash(mockId1);
            string hash2 = mockServer.GetHash(mockId2);
            Assert.AreEqual(hash1, GetFileHash(Path.Combine(Config.DataDir, hc.Id, "1_" + hc.Id)));
            Assert.AreEqual(hash2, GetFileHash(Path.Combine(Config.DataDir, hc.Id, "2_" + hc.Id)));
        }

        [Test]
        public void ParseTimeSucess()
        {
            Assert.AreEqual(Helpers.ParseTime(Helpers.RxDuration.Match(@"  Duration: 01:20:40.00, start: 11168.744000, bitrate: N/A")), 1 * 3600 + 20 * 60 + 40);
            Assert.AreEqual(Helpers.ParseTime(Helpers.RxTime.Match(@"data :frame=   54 fps=0.0 q=-1.0 size=     825kB time=01:20:40.00 bitrate=3031.6kbits/s")), 1 * 3600 + 20 * 60 + 40);
            Assert.AreEqual(Helpers.ParseTime(Helpers.RxTime.Match(@"    Stream #0:0: Video: h264 (High), yuv420p, 1280x720 [SAR 1:1 DAR 16:9], 30.30 fps, 30 tbr, 1k tbn, 60 tbc")), -1);
        }

        [Test]
        public void FFmpegInPath()
        {
            Assert.IsNotNull(Helpers.FindExecutableFromSystemPath("ffmpeg.exe"));
        }

        [Test]
        public async Task TestRedirect()
        {
            var hc = new HttpClient(new TestHandler(new HttpClientHandler()));
            var res = await hc.GetAsync("http://facebook.com").ConfigureAwait(false);
            Console.WriteLine(res.StatusCode + "" + res.RequestMessage.RequestUri + " " + res.Content.Headers.ContentType.MediaType);
        }

        class TestHandler : DelegatingHandler
        {
            public TestHandler(HttpMessageHandler handler) : base(handler) { }
            protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
            {
                var res = await base.SendAsync(request, cancellationToken).ConfigureAwait(false);
                request.RequestUri = new Uri("https://www.google.com/");
                res = await base.SendAsync(request, cancellationToken).ConfigureAwait(false);
                return res;
            }
        }
    }
}