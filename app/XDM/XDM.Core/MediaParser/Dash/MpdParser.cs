using System;
using System.Collections.Generic;
using System.Text;
using System.Text.RegularExpressions;
using System.Xml;
using System.IO;
using XDM.Core.MediaParser.Util;

#if !NET5_0_OR_GREATER
using XDM.Compatibility;
#endif

namespace XDM.Core.MediaParser.Dash
{
    public static class MpdParser
    {
        private static string trickModeUri = "http://dashif.org/guidelines/trickmode";
        private static readonly Regex TemplatePattern =
            new Regex(@"\$(RepresentationID|Time|Time%0(?<time_digits>[\d]+)(?<time_dx>[dx])?|Number|Number%0(?<num_digits>[\d]+)(?<num_dx>[dx])?|Bandwidth)\$");
        //@"\$(RepresentationID|Time|Time(%0([\d]+)([dx])?)?|Number|Number(%0([\d]+)([dx])?)?|Bandwidth)\$");
        public static IList<IList<(Representation Video, Representation Audio)>> Parse(
            string[] manifestLines,
            string playlistUrl)
        {
            using var memstream = new MemoryStream(Encoding.UTF8.GetBytes(string.Join("\n", manifestLines)));
            var xmldoc = new XmlDocument();
            xmldoc.Load(new XmlTextReader(memstream)
            {
                Namespaces = false
            });

            if (xmldoc.DocumentElement?.Name != "MPD")
                throw new Exception("Missing MPD start tag: " + xmldoc.DocumentElement.Name);
            if (xmldoc.DocumentElement.Attributes["type"]?.Value == "dynamic")
                throw new Exception("Manifest type dynamic is not supported");
            if (xmldoc.DocumentElement.SelectSingleNode("descendant::ContentProtection") != null)
                throw new Exception("Encrypted manifest");

            var mediaList = new List<IList<(Representation Video, Representation Audio)>>();

            var mediaPresentationDuration =
                DashUtil.ParseXsDuration(xmldoc.DocumentElement.Attributes["mediaPresentationDuration"]?.Value ?? "0");
            var baseUrl = new Uri(playlistUrl);
            var baseUrlNodeRoot = xmldoc.DocumentElement.SelectSingleNode("child::BaseURL");
            if (baseUrlNodeRoot != null)
            {
                baseUrl = UrlResolver.Resolve(baseUrl, baseUrlNodeRoot.InnerText);
            }

            var periods = xmldoc.DocumentElement.SelectNodes("child::Period");
            if (periods == null || periods.Count < 1) throw new Exception("No period found!");
            if (periods.Count > 1)
            {
                var periodDurations = CalculatePeriodDurationsIfMissing(periods, mediaPresentationDuration);
                for (var i = 0; i < periods.Count; i++)
                {
                    var period = periods[i];
                    mediaList.Add(ParsePeriod(period, baseUrl, periodDurations[i]));
                }
            }
            else if (periods.Count == 1)
            {
                mediaList.Add(ParsePeriod(periods[0], baseUrl, mediaPresentationDuration));
            }

            return mediaList;
        }

        private static IList<(Representation Video, Representation Audio)> ParsePeriod(XmlNode period, Uri baseUrl, long mediaPresentationDuration)
        {
            var periodDuration = mediaPresentationDuration;
            if (period.Attributes?["duration"] != null)
            {
                periodDuration = DashUtil.ParseXsDuration(period.Attributes["duration"]!.Value!);
            }
            var xmlBaseUrl = period.SelectSingleNode("child::BaseURL");
            if (xmlBaseUrl != null)
            {
                baseUrl = UrlResolver.Resolve(baseUrl, xmlBaseUrl.InnerText);
            }
            //first check if Indexed addressing is used
            //https://dashif-documents.azurewebsites.net/Guidelines-TimingModel/master/Guidelines-TimingModel.html#addressing-indexed
            var hasParentSegmentBase = period.SelectSingleNode("child::SegmentBase") != null;
            var xmlAdaptationSets = period.SelectNodes("child::AdaptationSet");
            var audioList = new List<Representation>();
            var videoList = new List<Representation>();
            for (var i = 0; i < xmlAdaptationSets?.Count; i++)
            {
                var representations = ParseAdaptationSet(xmlAdaptationSets[i], baseUrl, periodDuration, hasParentSegmentBase);
                if (representations.Count == 0) continue;
                if (representations[0].MimeType.StartsWith("audio"))
                {
                    audioList.AddRange(representations);
                }
                else
                {
                    videoList.AddRange(representations);
                }
            }

            var mediaList = new List<(Representation? Video, Representation? Audio)>();

            if (videoList.Count > 0 && audioList.Count > 0)
            {
                foreach (var video in videoList)
                {
                    foreach (var audio in audioList)
                    {
                        mediaList.Add((Video: video, Audio: audio));
                    }
                }
            }
            else if (videoList.Count > 0)
            {
                foreach (var video in videoList)
                {
                    mediaList.Add((Video: video, Audio: null));
                }
            }
            else if (audioList.Count > 0)
            {
                foreach (var audio in audioList)
                {
                    mediaList.Add((Video: null, Audio: audio));
                }
            }
            return mediaList;
        }

        private static IList<Representation> ParseAdaptationSet(XmlNode xmlAdaptationSet, Uri baseUrl, long periodDuration, bool hasParentSegmentBase)
        {
            var representations = new List<Representation>();
            var xmlBaseUrl = xmlAdaptationSet.SelectSingleNode("child::BaseURL");
            if (xmlBaseUrl != null)
            {
                baseUrl = UrlResolver.Resolve(baseUrl, xmlBaseUrl.InnerText);
            }
            if (ContainsTrickMode(xmlAdaptationSet))
            {
                return representations;
            }
            var xmlRepresentations = xmlAdaptationSet.SelectNodes("child::Representation");
            for (var i = 0; i < xmlRepresentations?.Count; i++)
            {
                var representation = ParseRepresentation(xmlRepresentations[i], baseUrl, periodDuration, hasParentSegmentBase);
                if (representation != null) representations.Add(representation);
            }
            return representations;
        }

        private static Representation ParseRepresentation(XmlNode xmlRepresentation, Uri baseUrl, long periodDuration, bool hasParentSegmentBase)
        {
            var mimeType = (xmlRepresentation?.Attributes?["mimeType"]?.Value ?? xmlRepresentation?.ParentNode?.Attributes?["mimeType"]?.Value ?? "").ToLowerInvariant();
            var width = xmlRepresentation.Attributes["width"]?.Value ?? xmlRepresentation.ParentNode.Attributes["width"]?.Value;
            var height = xmlRepresentation.Attributes["height"]?.Value ?? xmlRepresentation.ParentNode.Attributes["height"]?.Value;
            var bandwidth = xmlRepresentation.Attributes["bandwidth"]?.Value ?? xmlRepresentation.ParentNode.Attributes["bandwidth"]?.Value;
            var codec = xmlRepresentation.Attributes["codecs"]?.Value ?? xmlRepresentation.ParentNode.Attributes["codecs"]?.Value;
            var lang = xmlRepresentation.Attributes["lang"]?.Value ?? xmlRepresentation.ParentNode.Attributes["lang"]?.Value;

            if (!mimeType.StartsWith("audio") && !mimeType.StartsWith("video"))
            {
                return null;
            }

            var xmlBaseUrl = xmlRepresentation.SelectSingleNode("child::BaseURL");
            if (xmlBaseUrl != null)
            {
                baseUrl = UrlResolver.Resolve(baseUrl, xmlBaseUrl.InnerText);
            }
            if (xmlRepresentation.SelectSingleNode("child::SegmentBase") != null || hasParentSegmentBase)
            {
                //index addressing
                var segmentUri = baseUrl;
                return new Representation(new List<Uri> { segmentUri }, Int32.Parse(width ?? "-1"),
                                Int32.Parse(height ?? "-1"), codec, Int64.Parse(bandwidth ?? "-1"),
                                periodDuration, mimeType, lang);
            }
            else if (xmlRepresentation.SelectSingleNode("child::SegmentList") != null)
            {
                var xmlSegmentList = xmlRepresentation.SelectSingleNode("child::SegmentList");
                var xmlSegmentURLs = xmlSegmentList?.SelectNodes("child::SegmentURL");
                var segments = new List<Uri>();

                var xmlInit = xmlSegmentList.SelectSingleNode("child::Initialization") ??
                    xmlSegmentList.SelectSingleNode("child::RepresentationIndex");
                if (xmlInit != null)
                {
                    var sourceURL = xmlInit?.Attributes?["sourceURL"]?.Value;
                    segments.Add(UrlResolver.Resolve(baseUrl, sourceURL));
                }

                foreach (XmlNode xmlSegmentURL in xmlSegmentURLs)
                {
                    var media = xmlSegmentURL?.Attributes?["media"]?.Value;
                    segments.Add(UrlResolver.Resolve(baseUrl, media));
                }


                return segments.Count > 0 ? new Representation(segments, Int32.Parse(width ?? "-1"),
                                Int32.Parse(height ?? "-1"), codec, Int64.Parse(bandwidth ?? "-1"),
                                periodDuration, mimeType, lang) : null;
            }
            else
            {
                //simple or explicit addressing
                var xmlSegmentTemplate = xmlRepresentation.SelectSingleNode("child::SegmentTemplate") ??
                xmlRepresentation.ParentNode.SelectSingleNode("child::SegmentTemplate");
                if (xmlSegmentTemplate != null)
                {
                    var xmlSegmentTimeline = xmlSegmentTemplate.SelectSingleNode("child::SegmentTimeline");
                    //simple addressing
                    if (xmlSegmentTimeline == null)
                    {
                        var timescale = Int64.Parse(xmlSegmentTemplate?.Attributes?["timescale"]?.Value ?? "1");
                        var duration = Int64.Parse(xmlSegmentTemplate?.Attributes?["duration"]?.Value ?? "1");
                        var startNumber = Int64.Parse(xmlSegmentTemplate?.Attributes?["startNumber"]?.Value ?? "1");
                        var segmentCount = (int)Math.Ceiling(((double)periodDuration / 1000) / ((double)duration / timescale));
                        var representationId = xmlRepresentation.Attributes["id"].Value;
                        var number = startNumber;
                        var time = startNumber;

                        var initUrl = xmlSegmentTemplate?.Attributes?["initialization"]?.Value?.Replace("$$", "\0");
                        var mediaUrl = xmlSegmentTemplate?.Attributes?["media"]?.Value?.Replace("$$", "\0");
                        var mediaMatches = new List<Match>(TemplatePattern.Matches(mediaUrl).GetAsEnumerable());

                        var segments = new List<Uri>(segmentCount + (initUrl != null ? 1 : 0));

                        if (initUrl != null)
                        {
                            var initializationUrl = ParseTemplate(new List<Match>(TemplatePattern.Matches(initUrl).GetAsEnumerable()), initUrl,
                               number, time, bandwidth, representationId).Replace("\0", "$");
                            segments.Add(UrlResolver.Resolve(baseUrl, initializationUrl));
                        }

                        for (var i = 0; i < segmentCount; i++)
                        {
                            var segmentUrl = ParseTemplate(mediaMatches, mediaUrl, number,
                                time, bandwidth, representationId).Replace("\0", "$");
                            segments.Add(UrlResolver.Resolve(baseUrl, segmentUrl));
                            number++;
                            time += duration;
                        }
                        return segments.Count > 0 ? new Representation(segments, Int32.Parse(width ?? "-1"),
                                Int32.Parse(height ?? "-1"), codec, Int64.Parse(bandwidth ?? "-1"),
                                periodDuration, mimeType, lang) : null;
                    }
                    else if (xmlSegmentTimeline != null)
                    {
                        //explicit addressing
                        var xmlSs = xmlSegmentTimeline.SelectNodes("child::S");
                        if (xmlSs != null && xmlSs.Count > 0)
                        {
                            var representationId = xmlRepresentation.Attributes["id"].Value;
                            var number = Int64.Parse(xmlSegmentTemplate.Attributes["startNumber"]?.Value ?? "1");
                            var time = 0L;

                            var initUrl = xmlSegmentTemplate.Attributes["initialization"]?.Value?.Replace("$$", "\0");
                            var mediaUrl = xmlSegmentTemplate.Attributes["media"].Value.Replace("$$", "\0");
                            var mediaMatches = new List<Match>(TemplatePattern.Matches(mediaUrl).GetAsEnumerable());

                            var segments = new List<Uri>();

                            if (initUrl != null)
                            {
                                var initializationUrl = ParseTemplate(new List<Match>(TemplatePattern.Matches(initUrl).GetAsEnumerable()), initUrl,
                                   number, time, bandwidth, representationId).Replace("\0", "$");
                                segments.Add(UrlResolver.Resolve(baseUrl, initializationUrl));
                            }

                            for (var i = 0; i < xmlSs.Count; i++)
                            {
                                var xmls = xmlSs[i];
                                var d = Int64.Parse(xmls.Attributes["d"].Value);
                                var t = Int64.Parse(xmls.Attributes["t"]?.Value ?? "-1");
                                var r = Int64.Parse(xmls.Attributes["r"]?.Value ?? "-1");
                                if (t > 0) time = t;
                                var duration = d;

                                var segmentUrl = ParseTemplate(mediaMatches, mediaUrl, number,
                                    time, bandwidth, representationId).Replace("\0", "$");
                                segments.Add(UrlResolver.Resolve(baseUrl, segmentUrl));
                                number++;
                                time += duration;

                                if (r > 0)
                                {
                                    for (var k = 0; k < r; k++)
                                    {
                                        segmentUrl = ParseTemplate(mediaMatches, mediaUrl, number,
                                           time, bandwidth, representationId).Replace("\0", "$");
                                        segments.Add(UrlResolver.Resolve(baseUrl, segmentUrl));
                                        number++;
                                        time += duration;
                                    }
                                }
                            }

                            return segments.Count > 0 ? new Representation(segments, Int32.Parse(width ?? "-1"),
                                    Int32.Parse(height ?? "-1"), codec, Int64.Parse(bandwidth ?? "-1"),
                                    periodDuration, mimeType, lang) : null;
                        }
                    }
                }
            }
            return null;
        }

        private static string ParseTemplate(IEnumerable<Match> matches, string templateUrl, long number, long time, string bandwidth,
            string representationId)
        {
            foreach (Match match in matches)
            {
                var variable = match.Groups[1].Value;
                if (variable.StartsWith("Number"))
                {
                    if (variable == "Number")
                    {
                        templateUrl = templateUrl.Replace($"${variable}$", number.ToString());
                    }
                    else
                    {
                        var num = FormatDigit(number, match, true);
                        templateUrl = templateUrl.Replace($"${variable}$", num);
                    }
                }
                else if (variable.StartsWith("Time"))
                {
                    if (variable == "Time")
                    {
                        templateUrl = templateUrl.Replace($"${variable}$", time.ToString());
                    }
                    else
                    {
                        var num = FormatDigit(time, match, false);
                        templateUrl = templateUrl.Replace($"${variable}$", num);
                    }
                }
                else if (variable == "RepresentationID")
                {
                    templateUrl = templateUrl.Replace($"${variable}$", representationId);
                }
                else if (variable == "Bandwidth")
                {
                    templateUrl = templateUrl.Replace($"${variable}$", bandwidth);
                }
            }
            return templateUrl;
        }

        private static string FormatDigit(long digit, Match match, bool number)
        {
            var digitWidth = match.Groups[number ? "num_digits" : "time_digits"].Value;
            var dx = "D";
            if (match.Groups.ContainsKey("num_dx") || match.Groups.ContainsKey("time_dx"))
            {
                dx = number ? match.Groups["num_dx"].Value : match.Groups["time_dx"].Value;
            }
            return digit.ToString(dx + digitWidth);
        }

        public static IList<long> CalculatePeriodDurationsIfMissing(XmlNodeList periods, long mediaPresentationDuration)
        {
            var stack = new Stack<XmlNode>();
            foreach (XmlNode node in periods)
            {
                stack.Push(node);
            }
            var list = new List<long>(periods.Count);
            var last = mediaPresentationDuration;
            var count = stack.Count;
            for (int i = 0; i < count; i++)
            {
                var node = stack.Pop();
                var sduration = node.Attributes["duration"]?.Value;
                var sstart = node.Attributes["start"]?.Value;
                if (sstart == null && sduration == null)
                {
                    throw new Exception("Both period start and duration is missing");
                }
                if (sduration != null)
                {
                    var duration = DashUtil.ParseXsDuration(sduration);
                    list.Add(duration);
                    last = mediaPresentationDuration - duration;
                    continue;
                }
                if (sstart == null && i == stack.Count - 1)
                {
                    sstart = "PT0S";
                }
                var start = DashUtil.ParseXsDuration(sstart);
                list.Add(last - start);
                last = start;
            }
            list.Reverse();
            return list;
        }

        private static bool ContainsTrickMode(XmlNode xmlAdaptationSet)
        {
            var xmlEssentialProperty = xmlAdaptationSet.SelectSingleNode("child::EssentialProperty");
            if (xmlEssentialProperty != null)
            {
                var schemeIdUri = xmlEssentialProperty.Attributes?["schemeIdUri"]?.Value;
                if (trickModeUri == schemeIdUri)
                {
                    return true;
                }
            }
            var xmlSupplementalProperty = xmlAdaptationSet.SelectSingleNode("child::SupplementalProperty");
            if (xmlSupplementalProperty != null)
            {
                var schemeIdUri = xmlSupplementalProperty.Attributes?["schemeIdUri"]?.Value;
                if (trickModeUri == schemeIdUri)
                {
                    return true;
                }
            }
            return false;
        }

        public static IEnumerable<Match> GetAsEnumerable(this MatchCollection matchCollection)
        {
            foreach (Match item in matchCollection)
            {
                yield return item;
            }
        }

    }
}
