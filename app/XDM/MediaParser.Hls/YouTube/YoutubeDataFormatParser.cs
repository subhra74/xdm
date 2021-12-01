using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;

namespace MediaParser.YouTube
{
    public class YoutubeDataFormatParser
    {
        public static (List<ParsedDualUrlVideoFormat> DualVideoItems, List<ParsedUrlVideoFormat> VideoItems)
            GetFormats(string file)
        {
            var items = JsonConvert.DeserializeObject<VideoFormatData>(File.ReadAllText(file),
                new JsonSerializerSettings
                {
                    MissingMemberHandling = MissingMemberHandling.Ignore
                });

            var dualVideoItems = new List<ParsedDualUrlVideoFormat>();
            var videoItems = new List<ParsedUrlVideoFormat>();

            var maxOfEachQualityVideoGroupMp4 = items.StreamingData?.AdaptiveFormats
                .Where(i => i.MimeType.StartsWith("video/mp4") && i.Url != null)
                .GroupBy(x => x.QualityLabel)
                .Select(g => g.OrderByDescending(a => a.ContentLength / a.Bitrate).First());

            var maxOfEachQualityVideoGroupWebm = items.StreamingData?.AdaptiveFormats
                .Where(i => i.MimeType.StartsWith("video/webm") && i.Url != null)
                .GroupBy(x => x.QualityLabel)
                .Select(g => g.OrderByDescending(a => a.ContentLength / a.Bitrate).First());

            // .Select(g => g.OrderByDescending(a => a.Bitrate).First());

            var maxOfEachQualityAudioMp4 = items.StreamingData?.AdaptiveFormats
                .Where(i => i.MimeType.StartsWith("audio/mp4") && i.Url != null)
                .GroupBy(x => x.QualityLabel + x.MimeType)
                .Select(g => g.OrderByDescending(a => a.ContentLength / a.Bitrate).First());

            var maxOfEachQualityAudioWebm = items.StreamingData?.AdaptiveFormats
               .Where(i => i.MimeType.StartsWith("audio/webm") && i.Url != null)
               .GroupBy(x => x.QualityLabel + x.MimeType)
               .Select(g => g.OrderByDescending(a => a.ContentLength / a.Bitrate).First());

            if (maxOfEachQualityVideoGroupMp4 != null && maxOfEachQualityAudioMp4 != null)
            {
                foreach (var video in maxOfEachQualityVideoGroupMp4)
                {
                    foreach (var audio in maxOfEachQualityAudioMp4)
                    {
                        var ext = GetMediaExtension(video.MimeType, audio.MimeType);
                        dualVideoItems.Add(
                            new ParsedDualUrlVideoFormat(items.VideoDetails.Title,
                                video.Url,
                                audio.Url,
                                video.QualityLabel,
                                ext,
                                video.ContentLength + audio.ContentLength
                            )
                        );
                    }
                }
            }

            if (maxOfEachQualityVideoGroupWebm != null && maxOfEachQualityAudioWebm != null)
            {
                foreach (var video in maxOfEachQualityVideoGroupWebm)
                {
                    foreach (var audio in maxOfEachQualityAudioWebm)
                    {
                        var ext = GetMediaExtension(video.MimeType, audio.MimeType);
                        dualVideoItems.Add(
                            new ParsedDualUrlVideoFormat(items.VideoDetails.Title,
                                video.Url,
                                audio.Url,
                                video.QualityLabel,
                                ext,
                                video.ContentLength + audio.ContentLength
                            )
                        );
                    }
                }
            }

            //var videoList = new List<VideoFormat>();
            //var audioList = new List<VideoFormat>();

            //videoList.AddRange(items.StreamingData.AdaptiveFormats.Where(item => item.MimeType.StartsWith("video/")));
            //audioList.AddRange(items.StreamingData.AdaptiveFormats.Where(item => item.MimeType.StartsWith("audio/")));

            //var bestMp4Audio = BestAudioFormat("audio/mp4", audioList);
            //var bestWebmAudio = BestAudioFormat("audio/webm", audioList);

            //foreach (var video in videoList)
            //{
            //    foreach (var audio in new List<VideoFormat> { bestMp4Audio, bestWebmAudio })
            //    {
            //        dualVideoItems.Add(
            //            new ParsedDualUrlVideoFormat(
            //                ParseUrl(video.SignatureCipher),
            //                ParseUrl(audio.SignatureCipher),
            //                video.QualityLabel + " " + GetMediaExtension(video.MimeType, audio.MimeType)
            //            )
            //        );
            //    }
            //}

            if (items.StreamingData != null)
            {
                videoItems.AddRange(
                    items.StreamingData?.Formats.Where(
                        item => item.MimeType.StartsWith("video/") && item.Url != null).Select(
                            item => new ParsedUrlVideoFormat(items.VideoDetails.Title,
                                item.Url,
                                item.QualityLabel,
                                (item.MimeType.StartsWith("video/mp4") ? "MP4" : "MKV"),
                                item.ContentLength)));
            }

            return (dualVideoItems, videoItems);
        }

        private static VideoFormat BestAudioFormat(string mime, List<VideoFormat> audioList)
        {
            VideoFormat bestAudio = null;
            var highestBitrate = -1L;

            foreach (var audio in audioList)
            {
                if (audio.MimeType.StartsWith(mime))
                {
                    if (highestBitrate < audio.Bitrate)
                    {
                        highestBitrate = audio.Bitrate;
                        bestAudio = audio;
                    }
                }
            }

            return bestAudio;
        }

        private static string GetMediaExtension(string videoMime, string audioMime)
        {
            if (videoMime.StartsWith("video/mp4") && audioMime.StartsWith("audio/mp4"))
            {
                return "MP4";
            }
            return "MKV";
        }

        //private static string ParseUrl(string text)
        //{
        //    var arr = text.Split('&');
        //    var finalUrl = new StringBuilder();
        //    String url = null;
        //    foreach (var item in arr)
        //    {
        //        if (item.StartsWith("url"))
        //        {
        //            url = WebUtility.UrlDecode(item);
        //            continue;
        //        }
        //        finalUrl.Append('&');
        //        finalUrl.Append(item);
        //    }
        //    finalUrl.Insert(0, url.Substring(url.IndexOf('=') + 1));
        //    return finalUrl.ToString();
        //}
    }
}
