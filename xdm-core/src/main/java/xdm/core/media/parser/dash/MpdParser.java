package xdm.core.media.parser.dash;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import xdm.core.media.parser.util.UrlResolver;

public class MpdParser {
  private MpdParser() {}

  public static List<MpdEntry> parse(InputStream inputStream, String playlistUrl) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setNamespaceAware(false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document xmldoc = builder.parse(inputStream);

    Element root = xmldoc.getDocumentElement();
    if (!"MPD".equals(root.getNodeName())) {
      throw new MpdParserException("Missing MPD start tag: " + root.getNodeName());
    }
    if (root.hasAttribute("type") && "dynamic".equals(root.getAttribute("type"))) {
      throw new MpdParserException("Manifest type dynamic is not supported");
    }
    if (root.getElementsByTagName("ContentProtection").getLength() != 0) {
      throw new MpdParserException("Encrypted manifest");
    }

    List<MpdEntry> mediaList = new ArrayList<>();
    long mediaPresentationDuration =
        DashUtil.parseXsDuration(
            root.hasAttribute("mediaPresentationDuration")
                ? root.getAttribute("mediaPresentationDuration")
                : "0");
    URI baseUrl = new URI(playlistUrl);
    NodeList baseUrlNodeRoot = root.getElementsByTagName("BaseURL");
    if (baseUrlNodeRoot.getLength() > 0) {
      baseUrl = UrlResolver.resolve(baseUrl, baseUrlNodeRoot.item(0).getTextContent());
    }
    NodeList periods = root.getElementsByTagName("Period");
    if (periods.getLength() == 0) throw new MpdParserException("No period found!");
    if (periods.getLength() > 1) {
      List<Long> periodDurations =
          MissingDurationCalculator.calculatePeriodDurationsIfMissing(
              periods, mediaPresentationDuration);
      for (int i = 0; i < periods.getLength(); i++) {
        Node period = periods.item(i);
        mediaList.addAll(PeriodParser.parsePeriod(period, baseUrl, periodDurations.get(i)));
      }
    } else {
      mediaList.addAll(
          PeriodParser.parsePeriod(periods.item(0), baseUrl, mediaPresentationDuration));
    }
    return mediaList;
  }

  // Additional methods (ParseAdaptationSet, ContainsTrickMode, GetAsEnumerable) would be
  // implemented here as in the C# code
}
