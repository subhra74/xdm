using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using NUnit.Framework;
using System.Text.RegularExpressions;

namespace XDM.Tests
{
    class UrlTests
    {

        private static Regex ytPattern = new Regex(@".*(googlevideo|youtube).*videoplayback.*itag.*",
          RegexOptions.Compiled | RegexOptions.IgnoreCase);

        private static Regex ytParams = new Regex(@"([^=^&^?]+)=([^=^&]+)",
            RegexOptions.Compiled | RegexOptions.IgnoreCase);

        [SetUp]
        public void Setup()
        {
        }

        [Test]
        public void DeserializeBrowserMessageJsonSuccess()
        {
            Console.WriteLine( Test("https://rr5---sn-gwpa-jj06.googlevideo.com/videoplayback?expire=1687132952&ei=uEaPZMGfNoTL3LUP8oGV0A0&ip=2405%3A201%3A8015%3A4045%3A2800%3A8955%3Aeb08%3A246&id=o-AEOspwZBRcsUpvL3Zk66Y0WDCWH0GJ2VLlTRDtESfI-6&itag=396&aitags=133%2C134%2C135%2C136%2C137%2C160%2C242%2C243%2C244%2C247%2C248%2C278%2C394%2C395%2C396%2C397%2C398%2C399&source=youtube&requiressl=yes&mh=Ey&mm=31%2C29&mn=sn-gwpa-jj06%2Csn-qxaelne6&ms=au%2Crdu&mv=m&mvi=5&pl=45&initcwndbps=287500&siu=1&spc=qEK7B04bDG-C6-c8b8V5fKYG383GJr2xQVM_mL__9iLN92WGWvQ-MTw&vprv=1&svpuc=1&mime=video%2Fmp4&ns=2OmGX5LFR44apWPctiCOLjcN&gir=yes&clen=9364541&dur=387.966&lmt=1686678967068678&mt=1687111037&fvip=5&keepalive=yes&fexp=24007246%2C24363391&beids=24350018&c=WEB&txp=5537434&n=IqxM6kXl1-cy2g&sparams=expire%2Cei%2Cip%2Cid%2Caitags%2Csource%2Crequiressl%2Csiu%2Cspc%2Cvprv%2Csvpuc%2Cmime%2Cns%2Cgir%2Cclen%2Cdur%2Clmt&sig=AOq0QJ8wRgIhAJpzlZcW8XIuI9vNvdvfblPZiVng-4d4hdqYPJCENkTgAiEA5LleUvRGxc_ZjTbVBei9lANWVlRgq5ocvoGVE_VS6ls%3D&lsparams=mh%2Cmm%2Cmn%2Cms%2Cmv%2Cmvi%2Cpl%2Cinitcwndbps&lsig=AG3C_xAwRQIhAOK6WXPIRcXmIWJDMA_GvbbMyuYMhh5Ss_ksY_elWdKPAiB8gIx13hctFhMl8XXpn-7oV7MZfG73H_T7oEOY7_Gktg%3D%3D&alr=yes&cpn=KurAMdvm3LAhD_o_&cver=2.20230615.02.01&range=1019921-1986248&rn=9&rbuf=20601&pot=Mls8VKv9b-ATmDxsY4fPicT44OQE4pGeoVWjVref25chVDrycGxazVw35x8Q1iH0z7eobWy7sVtM4ZRBEXh6NZENrxTvQ1lysZ4KyWsvz6XdILgk489zxZyngwB1&ump=1&srfvp=1"));
        }

        static bool Test(string uri)
        {
            try
            {
                var url = new Uri(uri);
                if (!(url.Host.Contains(".youtube.") || url.Host.Contains(".googlevideo.")))
                {
                    return false;
                }

                if (!ytPattern.IsMatch(uri))
                {
                    return false;
                }

                var yt_url = new StringBuilder();
                yt_url.Append(url.GetLeftPart(UriPartial.Path));

                var matches = ytParams.Matches(url.Query);
                var contentType = string.Empty;

                int itag = 0;
                long clen = 0;
                string id = string.Empty;
                var first = true;

                foreach (Match match in matches)
                {
                    var groups = match.Groups;
                    if (groups.Count > 1)
                    {
                        var name = groups[1].Value;
                        var value = groups[2].Value;

                        switch (name)
                        {
                            case "mime":
                                contentType = Uri.UnescapeDataString(value);
                                break;
                            case "itag":
                                itag = Int32.Parse(value);
                                break;
                            case "clen":
                                clen = Int64.Parse(value);
                                break;
                            case "id":
                                id = value;
                                break;
                        }

                        if (name == "range")
                        {
                            continue;
                        }

                        if (first)
                        {
                            yt_url.Append('?');
                        }
                        else
                        {
                            yt_url.Append('&');
                        }

                        yt_url.Append(name + "=" + value);

                        first = false;
                    }
                }

                if (!(contentType != null && (contentType.Contains("audio/") ||
                   contentType.Contains("video/") ||
                   contentType.Contains("application/octet"))))
                {
                    return false;
                }

                return true;
            }
            catch { }
            return false;
        }
    }
}
