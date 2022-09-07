using XDM.Core.MediaParser.Util;
using System;
using System.Collections.Generic;

#if !NET5_0_OR_GREATER
using XDM.Compatibility;
#endif

namespace XDM.Core.MediaParser.Hls
{
    public static class HlsParser
    {
        public static readonly string AUDIO = "AUDIO";
        public static readonly string VIDEO = "VIDEO";
        public static readonly string EXT_X_STREAM_INF = "#EXT-X-STREAM-INF:";
        public static readonly string EXT_X_MEDIA = "#EXT-X-MEDIA:";
        public static readonly string EXT_X_BYTERANGE = "#EXT-X-BYTERANGE:";
        public static readonly string EXTINF = "#EXTINF:";
        public static readonly string EXT_X_MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:";
        public static readonly string EXT_X_KEY = "#EXT-X-KEY:";
        public static readonly string EXT_X_MAP = "#EXT-X-MAP:";
        public static readonly string EXT_X_I_FRAMES_ONLY = "#EXT-X-I-FRAMES-ONLY:";

        public static HlsPlaylist ParseMediaSegments(IEnumerable<string> manifestLines, string playlistUrl)
        {
            var mediaSegments = new List<HlsMediaSegment>();
            var mediaSequence = 0L;
            var startOffset = 0L;
            var segmentLength = 0L;
            var segmentEndOffset = 0L;
            var duration = 0.0;
            var totalDuration = 0.0;
            var hasByteRange = false;
            var isEncrypted = false;
            var baseUrl = new Uri(playlistUrl);
            var keyFrameOnly = false;

            Uri? keyUrl = null;
            string? iv = null;

            var sigFound = false;

            foreach (var lineText in manifestLines)
            {
                var line = lineText.Trim(' ', '\r');
                if (line.Length < 1) continue;
                if (!sigFound)
                {
                    if (line.StartsWith("#EXTM3U"))
                    {
                        sigFound = true;
                        continue;
                    }
                    else
                    {
                        return null;
                    }
                }
                if (line[0] != '#')
                {
                    var url = UrlResolver.Resolve(baseUrl, line);
                    mediaSegments.Add(new HlsMediaSegment(url)
                    {
                        ByteRange = new KeyValuePair<long, long>(startOffset, segmentLength),
                        Duration = duration,
                        KeyUrl = keyUrl,
                        IV = iv
                    });
                    mediaSequence++;
                    totalDuration += duration;
                }
                else if (line.StartsWith(EXT_X_I_FRAMES_ONLY))
                {
                    keyFrameOnly = true;
                }
                else if (line.StartsWith(EXT_X_BYTERANGE))
                {
                    hasByteRange = true;
                    var attrList = line.Substring(EXT_X_BYTERANGE.Length).Trim();
                    var kv = ParseByteRange(attrList);
                    long offset = kv.Key;
                    long length = kv.Value;
                    if (offset > 0)
                    {
                        startOffset = offset;
                    }
                    else
                    {
                        startOffset = segmentEndOffset;
                    }
                    segmentLength = length;
                    segmentEndOffset += segmentLength;
                }
                else if (line.StartsWith(EXTINF))
                {
                    var attrs = line.Substring(EXTINF.Length).Trim();
                    if (attrs.Length > 0)
                    {
                        duration = Double.Parse(attrs.Split(',')[0]);
                    }
                }
                else if (line.StartsWith(EXT_X_MEDIA_SEQUENCE))
                {
                    mediaSequence = Int32.Parse(line.Substring(EXT_X_MEDIA_SEQUENCE.Length).Trim());
                }
                else if (line.StartsWith(EXT_X_KEY))
                {
                    var encAttrs = HlsHelper.ParseAttributes(line.Substring(EXT_X_KEY.Length));
                    if (encAttrs.ContainsKey("METHOD"))
                    {
                        if (encAttrs["METHOD"] == "AES-128" && encAttrs.GetValueOrDefault("KEYFORMAT", "identity") == "identity")
                        {
                            isEncrypted = true;
                            keyUrl = UrlResolver.Resolve(baseUrl, encAttrs["URI"]);
                            iv = encAttrs.ContainsKey("IV") ? encAttrs["IV"] : mediaSequence.ToString("X");
                        }
                        else if (encAttrs["METHOD"] != "NONE")
                        {
                            return null;
                        }
                    }
                }
                else if (line.StartsWith(EXT_X_MAP))
                {
                    var encAttrs = HlsHelper.ParseAttributes(line.Substring(EXT_X_MAP.Length));
                    if (encAttrs.ContainsKey("URI"))
                    {
                        mediaSegments.Add(new HlsMediaSegment(UrlResolver.Resolve(baseUrl, encAttrs["URI"]))
                        {
                            ByteRange = encAttrs.ContainsKey("BYTERANGE") ? ParseByteRange(encAttrs["BYTERANGE"]) : new KeyValuePair<long, long>(0, 0),
                            Duration = 0,
                            KeyUrl = keyUrl,
                            IV = iv
                        });
                    }
                }
            }

            if (mediaSegments.Count > 0)
            {
                return new HlsPlaylist
                {
                    MediaSegments = mediaSegments,
                    HasByteRange = hasByteRange,
                    IsEncrypted = isEncrypted,
                    TotalDuration = totalDuration,
                    IsKeyIFrameOnly = keyFrameOnly
                };
            }

            return null;
        }

        public static List<HlsPlaylistContainer> ParseMasterPlaylist(IEnumerable<string> manifestLines, string playlistUrl)
        {
            var containers = new List<HlsPlaylistContainer>();
            var baseUrl = new Uri(playlistUrl);
            var sigFound = false;
            var mapExtStreamInf = new List<Dictionary<string, string>>();
            var mapExtMedia = new List<Dictionary<string, string>>();
            var urls = new List<Uri>();
            foreach (var lineText in manifestLines)
            {
                var line = lineText.Trim();
                if (line.Length < 1) continue;
                if (!sigFound)
                {
                    if (line.StartsWith("#EXTM3U"))
                    {
                        sigFound = true;
                        continue;
                    }
                    else
                    {
                        return null;
                    }
                }

                if (line[0] != '#')
                {
                    urls.Add(UrlResolver.Resolve(baseUrl, line));
                }
                else if (line.StartsWith(EXT_X_STREAM_INF))
                {
                    mapExtStreamInf.Add(HlsHelper.ParseAttributes(line.Substring(EXT_X_STREAM_INF.Length)));
                }
                else if (line.StartsWith(EXT_X_MEDIA))
                {
                    mapExtMedia.Add(HlsHelper.ParseAttributes(line.Substring(EXT_X_MEDIA.Length)));
                }
            }

            if (mapExtStreamInf.Count < 1) return null;

            for (var i = 0; i < urls.Count; i++)
            {
                var extStreamInf = mapExtStreamInf[i];
                if (extStreamInf.ContainsKey(AUDIO))
                {
                    var groupId = extStreamInf[AUDIO];
                    foreach (var media in mapExtMedia)
                    {
                        if (media["GROUP-ID"] == groupId && media["TYPE"] == AUDIO)
                        {
                            containers.Add(new HlsPlaylistContainer
                            {
                                VideoPlaylist = urls[i],
                                AudioPlaylist = media.ContainsKey("URI") ? UrlResolver.Resolve(baseUrl, media["URI"]) : null,
                                Attributes = MergeDict(extStreamInf, media)
                            });
                        }
                    }
                }
                else if (extStreamInf.ContainsKey(VIDEO))
                {
                    var groupId = extStreamInf[VIDEO];
                    foreach (var media in mapExtMedia)
                    {
                        if (media["GROUP-ID"] == groupId && media["TYPE"] == VIDEO)
                        {
                            containers.Add(new HlsPlaylistContainer
                            {
                                VideoPlaylist = media.ContainsKey("URI") ? UrlResolver.Resolve(baseUrl, media["URI"]) : null,
                                AudioPlaylist = urls[i],
                                Attributes = MergeDict(extStreamInf, media)
                            });
                        }
                    }
                }
                else
                {
                    containers.Add(new HlsPlaylistContainer
                    {
                        VideoPlaylist = urls[i],
                        Attributes = extStreamInf
                    });
                }
            }

            return containers;
        }

        private static Dictionary<string, string> MergeDict(Dictionary<string, string> d1, Dictionary<string, string> d2)
        {
            var dict = new Dictionary<string, string>();
            foreach (var ent in d1)
            {
                dict[ent.Key] = ent.Value;
            }
            foreach (var ent in d2)
            {
                dict[ent.Key] = ent.Value;
            }
            return dict;
        }

        private static KeyValuePair<long, long> ParseByteRange(string str)
        {
            long offset = 0, length;
            var attrs = str.Split('@');
            length = Int32.Parse(attrs[0]);
            if (attrs.Length == 2)
            {
                offset = Int32.Parse(attrs[1]);
            }
            return new KeyValuePair<long, long>(offset, length);
        }
    }
}
