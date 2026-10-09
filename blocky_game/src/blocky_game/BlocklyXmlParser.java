package blocky_game;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class to parse Blockly workspace XML into structured Map/List representations
 * suitable for GameEngine model rebuilding.
 */
public final class BlocklyXmlParser {

    private BlocklyXmlParser() {}

    public static List<Map<String, Object>> parseBlocklyXml(String xml) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        if (xml == null || xml.trim().isEmpty()) {
            return result;
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Element root = doc.getDocumentElement();

        // Top-level <block> elements directly inside <xml>
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "block".equalsIgnoreCase(n.getNodeName())) {
                Map<String, Object> block = parseBlockElement((Element) n);
                if (block != null) {
                    result.add(block);
                }
            }
        }
        return result;
    }

    private static Map<String, Object> parseBlockElement(Element el) {
        if (el == null) {
            return null;
        }
        Map<String, Object> map = new HashMap<>();
        map.put("type", el.getAttribute("type"));
        map.put("id", el.getAttribute("id"));

        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element child = (Element) n;
            String tag = child.getNodeName().toLowerCase();

            switch (tag) {
                case "field":
                    String fieldName = child.getAttribute("name");
                    String fieldVal = child.getTextContent().trim();
                    map.put(fieldName, fieldVal);
                    break;
                case "statement":
                    String stmtName = child.getAttribute("name");
                    Element stmtBlock = firstBlockChild(child);
                    if (stmtBlock != null) {
                        String key = "DO".equals(stmtName) ? "body" : ("ELSE".equals(stmtName) ? "elseBranch" : stmtName);
                        map.put(key, parseBlockElement(stmtBlock));
                    }
                    break;
                case "next":
                    Element nextBlock = firstBlockChild(child);
                    if (nextBlock != null) {
                        map.put("next", parseBlockElement(nextBlock));
                    }
                    break;
                default:
                    break;
            }
        }
        return map;
    }

    private static Element firstBlockChild(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "block".equalsIgnoreCase(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }
}
