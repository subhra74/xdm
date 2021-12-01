//using System;
//using System.Collections.Generic;
//using System.IO;
//using System.Linq;
//using System.Net.Http;
//using System.Text;
//using System.Threading;
//
//using XDM.Core.Lib.Common;

//namespace XDM.Core.Lib.Downloader.Hls
//{
//    internal class HlsChunkDownloader : HttpChunkDownloader
//    {
//        public HlsChunkDownloader(HlsChunk chunk, HttpClient http, CancellationToken cancellationToken, IChunkStreamMap chunkStreamMap, ICancelRequster cancelRequster) :
//            base(chunk, http, cancellationToken, chunkStreamMap, cancelRequster)
//        { 
//        }

//        protected override Stream PrepareOutStream()
//        {
//            var targetStream = new FileStream(_chunkStreamMap.GetStream(_chunk.Id), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.ReadWrite);
//            if (_chunk.Size > 0)
//            {
//                targetStream.Seek(_chunk.Offset + _chunk.Downloaded, SeekOrigin.Begin);
//            }
//            else
//            {
//                targetStream.Seek(0, SeekOrigin.Begin);
//            }

//            return targetStream;
//        }
//    }
//}
