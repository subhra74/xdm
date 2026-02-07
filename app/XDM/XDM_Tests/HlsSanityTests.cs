using System;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using NUnit.Framework;
using XDM.Core.Lib.Common;
using System.Threading;
using MediaParser.Hls;
using Serilog;
using XDM.Core.Lib.Common.MediaProcessor;
using System.Net;
using XDM.Core.Lib.Downloader.Adaptive.Hls;

namespace XDM.SystemTests
{
    public class HlsSanityTests
    {
        MockServer.MockServer mockServer;
        [SetUp]
        public void Setup()
        {
            ServicePointManager.DefaultConnectionLimit = 100;
            Config.DataDir = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
            Config.LoadConfig();
            Directory.CreateDirectory(Config.DataDir);

            Log.Logger = new LoggerConfiguration()
                .MinimumLevel.Debug()
                .WriteTo.Console()
                .CreateLogger();
        }

        [TearDown]
        public void TearDown()
        {
            mockServer?.Stop();
        }

        [TestCase(
@"
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:64
        #EXT-X-MEDIA-SEQUENCE:4
        #EXTINF:57.590867,
        hls4.ts
        #EXTINF:62.929533,
        hls5.ts
        #EXTINF:58.391667,
        hls6.ts
        #EXTINF:64.497767,
        hls7.ts
        #EXTINF:31.664967,
        hls8.ts
        #EXT-X-ENDLIST
        
")]
        public void ParseMediaSegmentsSuccess(string data)
        {
            var pl = HlsParser.ParseMediaSegments(data.Split('\n'),
                    "http://example/hls/playlist.m3u8");
            Assert.NotNull(pl);
            Assert.IsTrue(pl.TotalDuration > 0);
            Assert.IsTrue(pl.MediaSegments.Count > 0);
        }

        //        //[Ignore("Execute for special case")]
        //        [TestCase("http://localhost:8080/hls.m3u8")]
        //        public void DownloadAsyncRealUrlSuccess(string url)
        //        {
        //            string configDir = Path.GetTempPath();
        //            string id = Guid.NewGuid().ToString();

        //            string tempDir = @"C:\Users\subhro\Documents\IISExpress\DemoTS";//Path.Combine(Path.GetTempPath(), id);
        //            Directory.CreateDirectory(tempDir);

        //            Console.WriteLine(tempDir);

        //            var hc = new HlsDownloader(new HlsDownloadInfo
        //            {
        //                PlaylistContainer = new MediaParser.Hls.HlsPlaylistContainer
        //                {
        //                    Url1 = new Uri(url),
        //                }
        //            });
        //            hc.SetTargetDirectory(tempDir);
        //            hc.DownloadAsync().Wait();

        //            Console.WriteLine(hc.Duration);
        //            Assert.NotZero(hc.Duration);
        //        }

        [TestCase(".ts", ".ts")]
        [TestCase(".fmp4", ".mp4")]
        [TestCase(".m4s", ".mp4")]
        [TestCase(".mks", ".mkv")]
        public async Task DownloadAsyncWithMockSuccess(string segmentedExt, string expectedExt)
        {
            var n = 20;
            var random = new Random();
            mockServer = new MockServer.MockServer();
            var size = 0L;
            var mockIds = new string[n];
            var streams = new StringBuilder();
            for (var i = 0; i < n; i++)
            {
                var mockId = Guid.NewGuid().ToString() + segmentedExt;
                size += mockServer.AddMockHandler(mockId, start: 1, end: random.Next(1, 4)).Size;
                mockIds[i] = mockId;
                streams.Append(
                    $@"#EXTINF:57.590867
                    {mockServer.BaseUrl}{mockId}
                    ");
            }

            var playlist =
                $@"
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:64
                {streams}
                #EXT-X-ENDLIST
                ";
            var pid = Guid.NewGuid().ToString();
            mockServer.AddMockHandler(pid, contents: Encoding.UTF8.GetBytes(playlist));
            mockServer.StartAsync();

            var url = $"{mockServer.BaseUrl}{pid}";

            string configDir = Path.GetTempPath();
            string id = Guid.NewGuid().ToString();

            string tempDir = Path.Combine(Path.GetTempPath(), id);
            Directory.CreateDirectory(tempDir);

            Console.WriteLine(tempDir);

            var success = false;
            var cs = new CancellationTokenSource();

            var hc = new MultiSourceHLSDownloader(new MultiSourceHLSDownloadInfo
            {
                VideoUri = url
            },
            mediaProcessor: new FakeMediaProcessor());

            hc.Finished += (a, b) =>
            {
                Console.Write("Success");
                success = true;
                cs.Cancel();
            };
            hc.Failed += (a, b) =>
            {
                Console.Write("Failed");
                success = false;
                cs.Cancel();
            };
            hc.TargetDir = tempDir;
            hc.TargetFileName = "Sample";
            hc.Start();
            try
            {
                await Task.Delay(2 * 60 * 1000, cs.Token);
            }
            catch { }

            Assert.IsTrue(success);

            long size2 = hc.FileSize;

            Console.WriteLine(size2 + " " + size);

            Console.WriteLine(hc.Duration);
            Assert.NotZero(hc.Duration);
            Assert.AreEqual(size2, size);
            Assert.IsTrue(expectedExt.Equals(Path.GetExtension(hc.TargetFileName),
                StringComparison.InvariantCultureIgnoreCase));
        }

        private (string Url, long Size) CreateMockPlaylist(int n, MockServer.MockServer mockServer, string ext = "")
        {
            var random = new Random();
            var size = 0L;
            var mockIds = new string[n];
            var streams = new StringBuilder();
            for (var i = 0; i < n; i++)
            {
                var mockId = Guid.NewGuid().ToString() + ext;
                size += mockServer.AddMockHandler(mockId, start: 5, end: random.Next(7, 12)).Size;
                mockIds[i] = mockId;
                streams.Append(
                $@"#EXTINF:57.590867,
                                {mockServer.BaseUrl}{mockId}
                ");
            }

            var playlist = $@"
                                #EXTM3U
                                #EXT-X-VERSION:3
                                #EXT-X-TARGETDURATION:64
                                {streams}
                                #EXT-X-ENDLIST
                                ";

            var pid = Guid.NewGuid().ToString();
            mockServer.AddMockHandler(pid, contents: Encoding.UTF8.GetBytes(playlist));

            var url = $"{mockServer.BaseUrl}{pid}";
            return (url, size);
        }

        [Test]
        public async Task DownloadAsyncWithMockPauseResumeSuccess()
        {
            var success = false;
            var cs = new CancellationTokenSource();

            var n = 10;
            mockServer = new MockServer.MockServer();
            var pl = CreateMockPlaylist(n, mockServer);
            mockServer.StartAsync();

            string configDir = Path.GetTempPath();
            string id = Guid.NewGuid().ToString();

            string tempDir = Path.Combine(Path.GetTempPath(), id);
            Directory.CreateDirectory(tempDir);

            Console.WriteLine(tempDir);

            var hc = new MultiSourceHLSDownloader(new MultiSourceHLSDownloadInfo
            {
                VideoUri = pl.Url
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
            hc.TargetDir = tempDir;
            hc.TargetFileName = "Sample";
            hc.Start();

            await Task.Delay(2000);
            hc.Stop();
            Log.Information("Stopped --");
            await Task.Delay(2000);
            var name = hc.TargetFileName;

            cs = new CancellationTokenSource();
            hc = new MultiSourceHLSDownloader(hc.Id,
            mediaProcessor: new FakeMediaProcessor());
            hc.TargetDir = tempDir;
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

            long size2 = hc.FileSize;

            Console.WriteLine(size2 + " " + pl.Size);

            Console.WriteLine(hc.Duration);
            Assert.NotZero(hc.Duration);
            Assert.AreEqual(size2, pl.Size);
        }

        //        [TestCase(
        //"#EXTM3U\n" +
        //"#EXT-X-VERSION:6\n" +
        //"#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"group_audio128\",NAME=\"audio_0\",DEFAULT=YES,URI=\"stream_0.m3u8\"\n" +
        //"#EXT-X-STREAM-INF:BANDWIDTH=140800,CODECS=\"mp4a.40.2\",AUDIO=\"group_audio128\"\n" +
        //"stream_0.m3u8\n" +

        //"#EXT-X-STREAM-INF:BANDWIDTH=2340800,RESOLUTION=960x540,CODECS=\"avc1.64001f,mp4a.40.2\",AUDIO=\"group_audio128\"\n" +
        //"stream_1.m3u8\n" +

        //"#EXT-X-STREAM-INF:BANDWIDTH=6740800,RESOLUTION=1920x1080,CODECS=\"avc1.640028,mp4a.40.2\",AUDIO=\"group_audio128\"\n" +
        //"stream_2.m3u8\n"), TestCase(@"
        //#EXTM3U
        //#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=464000,RESOLUTION=640x360,CODECS=""avc1.77.30, mp4a.40.2"",CLOSED-CAPTIONS=NONE
        //https://multiplatform-f.akamaihd.net/i/multi/will/bunny/big_buck_bunny_,640x360_400,640x360_700,640x360_1000,950x540_1500,.f4v.csmil/index_0_av.m3u8
        //#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=764000,RESOLUTION=640x360,CODECS=""avc1.77.30, mp4a.40.2"",CLOSED-CAPTIONS=NONE
        //https://multiplatform-f.akamaihd.net/i/multi/will/bunny/big_buck_bunny_,640x360_400,640x360_700,640x360_1000,950x540_1500,.f4v.csmil/index_1_av.m3u8
        //#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1062000,RESOLUTION=640x360,CODECS=""avc1.77.30, mp4a.40.2"",CLOSED-CAPTIONS=NONE
        //https://multiplatform-f.akamaihd.net/i/multi/will/bunny/big_buck_bunny_,640x360_400,640x360_700,640x360_1000,950x540_1500,.f4v.csmil/index_2_av.m3u8
        //#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1563000,RESOLUTION=960x540,CODECS=""avc1.77.30, mp4a.40.2"",CLOSED-CAPTIONS=NONE
        //https://multiplatform-f.akamaihd.net/i/multi/will/bunny/big_buck_bunny_,640x360_400,640x360_700,640x360_1000,950x540_1500,.f4v.csmil/index_3_av.m3u8
        //#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=64000,CODECS=""mp4a.40.2"",CLOSED-CAPTIONS=NONE
        //https://multiplatform-f.akamaihd.net/i/multi/will/bunny/big_buck_bunny_,640x360_400,640x360_700,640x360_1000,950x540_1500,.f4v.csmil/index_0_a.m3u8

        //")]
        //        public void ParseMasterPlaylistSuccess(string data)
        //        {
        //            var pl = HlsParser.ParseMasterPlaylist(data.Split('\n'),
        //                    "http://example/hls/playlist.m3u8");
        //            Assert.NotNull(pl);
        //            Assert.IsTrue(pl.Count > 0);
        //        }

        [TestCase(".ts", ".ts")]
        [TestCase(".fmp4", ".mp4")]
        [TestCase(".m4s", ".mp4")]
        [TestCase(".mks", ".mkv")]
        public async Task DownloadAsync_With2Url_Success(string segmentedExt, string expectedExt)
        {
            var success = false;
            var cs = new CancellationTokenSource();

            var n = 10;
            mockServer = new MockServer.MockServer();
            var pl1 = CreateMockPlaylist(n, mockServer, segmentedExt);
            var pl2 = CreateMockPlaylist(n, mockServer, segmentedExt);

            mockServer.StartAsync();

            string configDir = Path.GetTempPath();
            string id = Guid.NewGuid().ToString();

            string tempDir = Path.Combine(Path.GetTempPath(), id);
            Directory.CreateDirectory(tempDir);

            Console.WriteLine(tempDir);

            var hc = new MultiSourceHLSDownloader(
                new MultiSourceHLSDownloadInfo
                {
                    VideoUri = pl1.Url,
                    AudioUri = pl2.Url
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
            hc.TargetDir = tempDir;
            hc.TargetFileName = "Sample";
            hc.Start();
            try
            {
                await Task.Delay(5 * 60 * 1000, cs.Token);
            }
            catch { }

            Assert.IsTrue(success);
            Console.WriteLine(hc.Duration);
            Assert.NotZero(hc.Duration);

            long size2 = hc.FileSize;

            Assert.AreEqual(pl1.Size + pl2.Size, size2);
            Assert.IsTrue(expectedExt.Equals(Path.GetExtension(hc.TargetFileName),
                StringComparison.InvariantCultureIgnoreCase));
        }

        [TestCase(".ts", ".ts")]
        [TestCase(".fmp4", ".mp4")]
        [TestCase(".m4s", ".mp4")]
        [TestCase(".mks", ".mkv")]
        public async Task DownloadAsync_With2Url_PauseResume_Success(string segmentedExt, string expectedExt)
        {
            var success = false;
            var cs = new CancellationTokenSource();

            var n = 20;
            mockServer = new MockServer.MockServer();
            var pl1 = CreateMockPlaylist(n, mockServer, segmentedExt);
            var pl2 = CreateMockPlaylist(n, mockServer, segmentedExt);

            mockServer.StartAsync();

            string configDir = Path.GetTempPath();
            string id = Guid.NewGuid().ToString();

            string tempDir = Path.Combine(Path.GetTempPath(), id);
            Directory.CreateDirectory(tempDir);

            var hc = new MultiSourceHLSDownloader(
                new MultiSourceHLSDownloadInfo
                {
                    VideoUri = pl1.Url,
                    AudioUri = pl2.Url
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
            hc.TargetDir = tempDir;
            hc.TargetFileName = "Sample";
            hc.Start();

            await Task.Delay(10000);
            hc.Stop();
            Log.Information("Stopped --");
            await Task.Delay(2000);
            var name = hc.TargetFileName;

            cs = new CancellationTokenSource();
            hc = new MultiSourceHLSDownloader(hc.Id,
            mediaProcessor: new FakeMediaProcessor());
            hc.TargetDir = tempDir;
            hc.TargetFileName = name;
            hc.Finished += (a, b) =>
            {
                Log.Debug("Finished2");
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
                await Task.Delay(15 * 60 * 1000, cs.Token);
            }
            catch { }

            Assert.IsTrue(success);

            long size2 = hc.FileSize;
            //foreach (var f in Directory.EnumerateFiles(Path.Combine(Config.DataDir, hc.Id)))
            //{
            //    size2 += new FileInfo(f).Length;
            //}
            Log.Debug(size2 + " " + (pl1.Size + pl2.Size));
            Assert.AreEqual(pl1.Size + pl2.Size, size2);

            Log.Debug(expectedExt + " " + Path.GetExtension(hc.TargetFileName));

            Assert.IsTrue(expectedExt.Equals(Path.GetExtension(hc.TargetFileName),
                StringComparison.InvariantCultureIgnoreCase));
        }

        //        [TestCase(
        //            @"
        //        #EXTM3U
        //        #EXT-X-VERSION:4
        //        #EXT-X-TARGETDURATION:20
        //        #EXT-X-MEDIA-SEQUENCE:0
        //        #EXTINF:20.000000,
        //        #EXT-X-BYTERANGE:1629960@0
        //        out.ts
        //        #EXTINF:20.000000,
        //        #EXT-X-BYTERANGE:2849328@1629960
        //        out.ts
        //        #EXTINF:20.000000,
        //        #EXT-X-BYTERANGE:1252644@4479288
        //        out.ts
        //        #EXTINF:19.800000,
        //        #EXT-X-BYTERANGE:1100740@5731932
        //        out.ts
        //        #EXT-X-ENDLIST
        //        "
        //            )]
        //        public void ParseMediaSegments_WithByteRange_Success(string data)
        //        {
        //            var pl = HlsParser.ParseMediaSegments(data.Split('\n'),
        //                    "http://example/hls/playlist.m3u8");
        //            Assert.NotNull(pl);
        //            Assert.IsTrue(pl.MediaSegments.Count > 0);
        //        }

        //        [Test]
        //        public void DownloadAsync_WithByteRange_Success()
        //        {
        //            var mockServer = new MockServer.MockServer();

        //            var mockId = Guid.NewGuid().ToString();
        //            var ret = mockServer.AddMockHandler(mockId, fixedSize: 6832672);

        //            var contentUrl = $"http://127.0.0.1:39000/{mockId}";
        //            var pl = $@"
        //        #EXTM3U
        //        #EXT-X-VERSION:4
        //        #EXT-X-TARGETDURATION:20
        //        #EXT-X-MEDIA-SEQUENCE:0
        //        #EXTINF:20.000000,
        //        #EXT-X-BYTERANGE:1629960@0
        //        {contentUrl}
        //        #EXTINF:20.000000,
        //        #EXT-X-BYTERANGE:2849328@1629960
        //        {contentUrl}
        //        #EXTINF:20.000000,
        //        #EXT-X-BYTERANGE:1252644@4479288
        //        {contentUrl}
        //        #EXTINF:19.800000,
        //        #EXT-X-BYTERANGE:1100740@5731932
        //        {contentUrl}
        //        #EXT-X-ENDLIST";
        //            var pid = Guid.NewGuid().ToString();
        //            var url = $"http://127.0.0.1:39000/{pid}";

        //            mockServer.AddMockHandler(pid, contents: Encoding.UTF8.GetBytes(pl));

        //            mockServer.StartAsync();

        //            string configDir = Path.GetTempPath();
        //            string id = Guid.NewGuid().ToString();

        //            string tempDir = @"C:\Users\subhro\Documents\IISExpress\DemoTS";//Path.Combine(Path.GetTempPath(), id);
        //            Directory.CreateDirectory(tempDir);

        //            Console.WriteLine(tempDir);

        //            var hc = new HlsDownloader(new HlsDownloadInfo
        //            {
        //                PlaylistContainer = new MediaParser.Hls.HlsPlaylistContainer
        //                {
        //                    Url1 = new Uri(url)
        //                }
        //            });
        //            hc.SetTargetDirectory(tempDir);
        //            hc.DownloadAsync().Wait();

        //            Console.WriteLine(hc.Duration);
        //            Assert.NotZero(hc.Duration);

        //            long size2 = 0;
        //            foreach (var f in Directory.EnumerateFiles(Path.Combine(Config.DataDir, hc.Id)))
        //            {
        //                size2 += new FileInfo(f).Length;
        //            }

        //            Assert.AreEqual(ret.Size, size2);
        //            Assert.AreEqual(ret.Hash, GetFileHash(Directory.GetFiles(Path.Combine(Config.DataDir, hc.Id))[0]));
        //        }
    }

    class FakeMediaProcessor : BaseMediaProcessor
    {
        public override MediaProcessingResult MergeAudioVideStream(string file1, string file2, string outfile, CancelFlag cancellationToken, out long outFileSize)
        {
            Log.Information(file1 + " " + file2 + " " + outfile);
            outFileSize = new FileInfo(file1).Length + new FileInfo(file2).Length;
            return MediaProcessingResult.Success;
        }

        public override MediaProcessingResult MergeHLSAudioVideStream(string segmentListFile, string outfile, CancelFlag cancellationToken, out long outFileSize)
        {
            Log.Information(segmentListFile + " " + outfile);
            outFileSize = -1;
            return MediaProcessingResult.Success;
        }
    }
}




