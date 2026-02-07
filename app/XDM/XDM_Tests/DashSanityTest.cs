using MediaParser.Dash;
using NUnit.Framework;
using Serilog;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using XDM.Core.Lib.Common;

namespace XDM.SystemTests
{
    public class DashSanityTest
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
        [TestCase("PT0H10M54.00S")]
        public void ParseXsDurationSuccess(string duration)
        {
            Assert.AreEqual(DashUtil.ParseXsDuration(duration), (10 * 60 + 54) * 1000);
        }

        [TestCase(@"<?xml version=""1.0"" encoding=""utf-8""?>
<MPD xmlns=""urn:mpeg:dash:schema:mpd:2011"" xmlns:xsi=""http://www.w3.org/2001/XMLSchema-instance"" profiles=""urn:mpeg:dash:profile:isoff-live:2011"" type=""static"" mediaPresentationDuration=""PT2M59.281S"" minBufferTime=""PT3S""><Period><AdaptationSet id=""1"" group=""1"" profiles=""ccff"" bitstreamSwitching=""false"" segmentAlignment=""true"" contentType=""video"" mimeType=""video/mp4"" codecs=""avc1.640028"" maxWidth=""1920"" maxHeight=""1080"" startWithSAP=""1""><SegmentTemplate timescale=""10000000"" media=""QualityLevels($Bandwidth$)/Fragments(video=$Time$,format=mpd-time-csf)"" initialization=""QualityLevels($Bandwidth$)/Fragments(video=i,format=mpd-time-csf)""><SegmentTimeline><S d=""20020000"" r=""88""/><S d=""10343666""/></SegmentTimeline></SegmentTemplate><Representation id=""1_V_video_1"" bandwidth=""5977913"" width=""1920"" height=""1080""/><Representation id=""1_V_video_2"" bandwidth=""4681440"" width=""1920"" height=""1080""/><Representation id=""1_V_video_3"" bandwidth=""3385171"" codecs=""avc1.64001F"" width=""1280"" height=""720""/><Representation id=""1_V_video_4"" bandwidth=""2238364"" codecs=""avc1.64001F"" width=""960"" height=""540""/><Representation id=""1_V_video_5"" bandwidth=""1490441"" codecs=""avc1.64001F"" width=""960"" height=""540""/><Representation id=""1_V_video_6"" bandwidth=""991868"" codecs=""avc1.64001E"" width=""640"" height=""360""/><Representation id=""1_V_video_7"" bandwidth=""642832"" codecs=""avc1.64001E"" width=""640"" height=""360""/><Representation id=""1_V_video_8"" bandwidth=""393546"" codecs=""avc1.64000D"" width=""320"" height=""180""/></AdaptationSet><AdaptationSet id=""2"" group=""5"" profiles=""ccff"" bitstreamSwitching=""false"" segmentAlignment=""true"" contentType=""audio"" mimeType=""audio/mp4"" codecs=""mp4a.40.2""><Label>AAC_und_ch2_128kbps</Label><SegmentTemplate timescale=""10000000"" media=""QualityLevels($Bandwidth$)/Fragments(AAC_und_ch2_128kbps=$Time$,format=mpd-time-csf)"" initialization=""QualityLevels($Bandwidth$)/Fragments(AAC_und_ch2_128kbps=i,format=mpd-time-csf)""><SegmentTimeline><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361"" r=""1""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361"" r=""1""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361"" r=""1""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361"" r=""1""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361"" r=""1""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361"" r=""1""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361"" r=""1""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""15092971""/></SegmentTimeline></SegmentTemplate><Representation id=""5_A_AAC_und_ch2_128kbps_1"" bandwidth=""125615"" audioSamplingRate=""44100""/></AdaptationSet><AdaptationSet id=""3"" group=""5"" profiles=""ccff"" bitstreamSwitching=""false"" segmentAlignment=""true"" contentType=""audio"" mimeType=""audio/mp4"" codecs=""mp4a.40.2""><Label>AAC_und_ch2_56kbps</Label><SegmentTemplate timescale=""10000000"" media=""QualityLevels($Bandwidth$)/Fragments(AAC_und_ch2_56kbps=$Time$,format=mpd-time-csf)"" initialization=""QualityLevels($Bandwidth$)/Fragments(AAC_und_ch2_56kbps=i,format=mpd-time-csf)""><SegmentTimeline><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361"" r=""1""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361"" r=""1""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361"" r=""1""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361"" r=""1""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361"" r=""1""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361"" r=""1""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361"" r=""1""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""20201361""/><S d=""20201360""/><S d=""15092971""/></SegmentTimeline></SegmentTemplate><Representation id=""5_A_AAC_und_ch2_56kbps_1"" bandwidth=""53620"" audioSamplingRate=""44100""/></AdaptationSet></Period></MPD>
")]
        [TestCase(@"<?xml version=""1.0"" encoding=""UTF-8""?>
<MPD type=""static"" xmlns=""urn:mpeg:dash:schema:mpd:2011"" profiles=""urn:mpeg:dash:profile:isoff-live:2011"" minBufferTime=""PT0.451S"" mediaPresentationDuration=""PT9M32.520S"">
<!-- MPEG DASH ISO BMFF test stream with avc3 -->
<!-- BBC Research & Development -->
<!-- For more information see http://rdmedia.bbc.co.uk -->
<ProgramInformation>
	<Title>Adaptive Bitrate Test Stream from BBC Research and Development - Full stream with separate initialisation segments</Title>
	<Source>BBC Research and Development</Source>
</ProgramInformation>
<Period duration=""PT9M32.520S"" start=""PT0S"">
	<AdaptationSet startWithSAP=""2"" segmentAlignment=""true"" id=""1"" sar=""1:1"" frameRate=""25"" scanType=""progressive"" mimeType=""video/mp4"" >
		<BaseURL>avc3/</BaseURL>
		<SegmentTemplate timescale=""1000"" duration=""3840"" media=""$RepresentationID$/$Number%06d$.m4s"" initialization=""$RepresentationID$/IS.mp4"" />
		<Representation id=""1920x1080p25"" codecs=""avc3.640028"" height=""1080"" width=""1920"" bandwidth=""4741120"" />
		<Representation id=""896x504p25"" codecs=""avc3.64001f"" height=""504"" width=""896"" bandwidth=""1416688"" />
		<Representation id=""704x396p25"" codecs=""avc3.4d401e"" height=""396"" width=""704"" bandwidth=""843768"" />
		<Representation id=""512x288p25"" codecs=""avc3.4d4015"" height=""288"" width=""512"" bandwidth=""449480"" />
		<Representation id=""1280x720p25"" codecs=""avc3.640020"" height=""720"" width=""1280"" bandwidth=""2656696"" />
	</AdaptationSet>
	<AdaptationSet startWithSAP=""2"" segmentAlignment=""true"" id=""3"" codecs=""mp4a.40.2"" audioSamplingRate=""48000"" lang=""eng"" mimeType=""audio/mp4"" >
		<AudioChannelConfiguration schemeIdUri=""urn:mpeg:dash:23003:3:audio_channel_configuration:2011"" value=""2""/>
		<BaseURL>audio/</BaseURL>
		<SegmentTemplate timescale=""1000"" duration=""3840"" media=""$RepresentationID$/$Number%06d$.m4s"" initialization=""$RepresentationID$/IS.mp4"" />
		<Representation id=""160kbps"" bandwidth=""160000"" />
		<Representation id=""96kbps"" bandwidth=""96000"" />
		<Representation id=""128kbps"" bandwidth=""128000"" />
	</AdaptationSet>
</Period>
</MPD>")]
        [TestCase(@"<?xml version=""1.0"" encoding=""utf-8""?>
<MPD xmlns:xsi=""http://www.w3.org/2001/XMLSchema-instance"" xmlns=""urn:mpeg:dash:schema:mpd:2011"" xsi:schemaLocation=""urn:mpeg:DASH:schema:MPD:2011 DASH-MPD.xsd"" profiles=""urn:mpeg:dash:profile:isoff-on-demand:2011"" minBufferTime=""PT15S"" type=""static"" mediaPresentationDuration=""PT14M48S"">
  <Period start=""PT0S"">
    <AdaptationSet codecs=""mp4a.40.42"" contentType=""audio"" mimeType=""audio/mp4"" lang=""en"" segmentAlignment=""true"" bitstreamSwitching=""true"" audioSamplingRate=""48000"">
      <AudioChannelConfiguration schemeIdUri=""urn:mpeg:mpegB:cicp:ChannelConfiguration"" value=""2""/>
      <SegmentTemplate timescale=""48000"" duration=""245760"" initialization=""sintel_audio_video_brs-$RepresentationID$_init.mp4"" media=""sintel_au$$dio_video_brs-$RepresentationID$_$Number$.m4s"" startNumber=""0""/>
      <Representation id=""eng-2-xheaac-16kbps"" bandwidth=""17188""/>
      <Representation id=""eng-2-xheaac-32kbps"" bandwidth=""33538""/>
      <Representation id=""eng-2-xheaac-64kbps"" bandwidth=""66115""/>
      <Representation id=""eng-2-xheaac-128kbps"" bandwidth=""129717""/>
    </AdaptationSet>
    <AdaptationSet codecs=""avc1.42c01e"" contentType=""video"" frameRate=""24/1"" width=""848"" height=""386"" mimeType=""video/mp4"" par=""21:9"" segmentAlignment=""true"" bitstreamSwitching=""true"">
      <SegmentTemplate timescale=""24"" duration=""120"" initialization=""sintel_audio_video_brs-$RepresentationID$_init.mp4"" media=""sintel_audio_video_brs-$RepresentationID$_$Number$.m4s"" startNumber=""0""/>
      <Representation id=""848x386-1500kbps"" bandwidth=""1422029"" sar=""1:1""/>
    </AdaptationSet>
  </Period>
</MPD>
<!--	https://dash.akamaized.net/dash264/TestCasesMCA/fraunhofer/xHE-AAC_Stereo/2/Sintel/sintel_audio_video_brs.mpd  -->
")]
        [TestCase(@"<?xml version=""1.0""  encoding=""utf-8""?>
<MPD >
    <Period duration=""PT30S"">
        <BaseURL>ad/</BaseURL>
        <!-- Everything in one Adaptation Set -->
        <AdaptationSet mimeType=""video/mp2t"">
            <!-- 720p Representation at 3.2 Mbps -->
            <Representation id=""720p"" bandwidth=""3200000"" width=""1280"" height=""720"">
                <!-- Just use one segment, since the ad is only 30 seconds long -->
                <BaseURL>720p.ts</BaseURL>
                <SegmentBase>
                    <RepresentationIndex sourceURL=""720p.sidx""/>
                </SegmentBase>
            </Representation>
            <!-- 1080p Representation at 6.8 Mbps -->
            <Representation id=""1080p"" bandwidth=""6800000"" width=""1920""
                            height=""1080"">
                <BaseURL>1080p.ts</BaseURL>
                <SegmentBase>
                    <RepresentationIndex sourceURL=""1080p.sidx""/>
                </SegmentBase>
            </Representation>
        </AdaptationSet>
    </Period>
    <!-- Normal Content -->
    <Period duration=""PT10M"">
        <BaseURL>main/</BaseURL>
        <!-- Just the video -->
        <AdaptationSet mimeType=""video/mp2t"">
            <BaseURL>video/</BaseURL>
            <!-- 720p Representation at 3.2 Mbps -->
            <Representation id=""720p"" bandwidth=""3200000"" width=""1280"" height=""720"">
                <BaseURL>720p/</BaseURL>
                <!-- First, we'll just list all of the segments -->
                <!-- Timescale is ""ticks per second"", so each segment is 1 minute
                     long -->
                <SegmentList timescale=""90000"" duration=""5400000"">
                    <RepresentationIndex sourceURL=""representation-index.sidx""/>
                    <SegmentURL media=""segment-1.ts""/>
                    <SegmentURL media=""segment-2.ts""/>
                    <SegmentURL media=""segment-3.ts""/>
                    <SegmentURL media=""segment-4.ts""/>
                    <SegmentURL media=""segment-5.ts""/>
                    <SegmentURL media=""segment-6.ts""/>
                    <SegmentURL media=""segment-7.ts""/>
                    <SegmentURL media=""segment-8.ts""/>
                    <SegmentURL media=""segment-9.ts""/>
                    <SegmentURL media=""segment-10.ts""/>
                </SegmentList>
            </Representation>
            <!-- 1080p Representation at 6.8 Mbps -->
            <Representation id=""1080p"" bandwidth=""6800000"" width=""1920""
                            height=""1080"">
                <BaseURL>1080/</BaseURL>
                <!-- Since all of our segments have similar names, this time
                     we'll use a SegmentTemplate -->
                <SegmentTemplate media=""segment-$Number$.ts"" timescale=""90000"">
                    <RepresentationIndex sourceURL=""representation-index.sidx""/>
                    <!-- Let's add a SegmentTimeline so the client can easily see
                         how many segments there are -->
                    <SegmentTimeline>
                        <!-- r is the number of repeats _after_ the first one, so
                             this reads:
                             Starting from time 0, there are 10 (9 + 1) segments
                             with a duration of (5400000 / @timescale) seconds. -->
                        <S t=""0"" r=""9"" d=""5400000""/>
                    </SegmentTimeline>
                </SegmentTemplate>
            </Representation>
        </AdaptationSet>
        <!-- Just the audio -->
        <AdaptationSet mimeType=""audio/mp2t"">
            <BaseURL>audio/</BaseURL>
            <!-- We're just going to offer one audio representation, since audio
                 bandwidth isn't very important. -->
            <Representation id=""audio"" bandwidth=""128000"">
                <SegmentTemplate media=""segment-$Number$.ts"" timescale=""90000"">
                    <RepresentationIndex sourceURL=""representation-index.sidx""/>
                    <SegmentTimeline>
                        <S t=""0"" r=""9"" d=""5400000""/>
                    </SegmentTimeline>
                </SegmentTemplate>
            </Representation>
            <Representation id=""audio2"" bandwidth=""128000"">
                <SegmentTemplate media=""segment-$Number$.ts"" timescale=""90000"" duration=""5400000"">
                    <RepresentationIndex sourceURL=""representation-index.sidx""/>
                </SegmentTemplate>
            </Representation>
        </AdaptationSet>
    </Period>
</MPD>
")]
        public void ParseSuccess(string mpdText)
        {
            var ret = MpdParser.Parse(mpdText.Split('\n'), 
                "https://dash.akamaized.net/dash264/TestCasesMCA/fraunhofer/xHE-AAC_Stereo/2/Sintel/sintel_audio_video_brs.mpd ");
            Assert.IsTrue(ret.Count > 0);
        }
    }
}
