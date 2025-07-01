package xdm.core.media.parser.dash;

import java.net.URI;
import java.util.List;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import xdm.core.media.parser.util.UrlResolver;

public class XmlUtil {
  private XmlUtil() {}

  public static String getAttr(Node node, String attrName) {
    Node child = node.getAttributes().getNamedItem(attrName);
    if (child != null) {
      return child.getNodeValue();
    }
    return null;
  }

  public static String getFirstTagValue(Node node, String childTagName) {
    NodeList nodeList = ((org.w3c.dom.Element) node).getElementsByTagName(childTagName);
    if (nodeList.getLength() > 0) {
      return nodeList.item(0).getTextContent();
    }
    return null;
  }

  public static Node getFirstTag(Node node, String childTagName) {
    if (node == null) {
      return null;
    }
    NodeList nodeList = ((org.w3c.dom.Element) node).getElementsByTagName(childTagName);
    if (nodeList.getLength() > 0) {
      return nodeList.item(0);
    }
    return null;
  }

  public static Node findTag(Node node, List<String> tags) {
    for (String tag : tags) {
      NodeList nodeList = ((org.w3c.dom.Element) node).getElementsByTagName(tag);
      if (nodeList.getLength() > 0) {
        return nodeList.item(0);
      }
    }
    return null;
  }

  public static boolean containsTag(Node node, String childTagName) {
    return ((org.w3c.dom.Element) node).getElementsByTagName(childTagName).getLength() > 0;
  }

  public static String getSelfOrParentAttr(
      NamedNodeMap selfAttrs, NamedNodeMap parentAttrs, String attrName) {
    Node attr = null;
    if (selfAttrs != null) {
      attr = selfAttrs.getNamedItem(attrName);
    }
    if (attr == null && parentAttrs != null) {
      attr = parentAttrs.getNamedItem(attrName);
    }
    if (attr != null) {
      return attr.getNodeValue();
    }
    return null;
  }

  public static URI resolveBaseUrl(Node node, URI baseUrl) {
    String baseUrlValue = XmlUtil.getFirstTagValue(node, "BaseURL");
    if (baseUrlValue != null) {
      return UrlResolver.resolve(baseUrl, baseUrlValue);
    }
    return baseUrl;
  }

  public static Node findSelfOrParentTag(Node node, String name) {
    Node selfNode = getFirstTag(node, name);
    if (selfNode != null) {
      return selfNode;
    }
    Node parent = node.getParentNode();
    return getFirstTag(parent, name);
  }
}
