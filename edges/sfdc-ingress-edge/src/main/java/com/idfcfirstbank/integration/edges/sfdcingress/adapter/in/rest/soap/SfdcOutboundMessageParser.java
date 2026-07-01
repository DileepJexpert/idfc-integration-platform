package com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Framework-free parser: an SFDC SOAP Outbound Message (XML text) → a structured
 * {@link SfdcOutboundMessage}. Pure and I/O-free (parses an in-memory string), so
 * it unit-tests against the golden fixture with no Spring context.
 *
 * <p>Matches elements by LOCAL name (namespace-agnostic) so it is robust to the
 * {@code soapenv}/default-outbound/{@code sf1} prefixes SFDC uses, and scopes each
 * lookup to DIRECT children so the two distinct {@code <Id>} elements
 * ({@code Notification/Id} vs {@code sObject/sf1:Id}) never collide.
 *
 * <p>Hardened against XXE: DTDs and external entities are disabled — an outbound
 * message never legitimately carries them.
 */
public class SfdcOutboundMessageParser {

    public SfdcOutboundMessage parse(String soapXml) {
        if (soapXml == null || soapXml.isBlank()) {
            throw new SoapParseException("empty SOAP body");
        }
        Document doc = parseSecurely(soapXml);

        Element notifications = firstDescendantByLocalName(doc.getDocumentElement(), "notifications");
        if (notifications == null) {
            throw new SoapParseException("no <notifications> element in SOAP envelope");
        }

        List<SoapNotification> parsed = new ArrayList<>();
        for (Element n : directChildrenByLocalName(notifications, "Notification")) {
            parsed.add(parseNotification(n));
        }

        return new SfdcOutboundMessage(
                childText(notifications, "OrganizationId"),
                childText(notifications, "ActionId"),
                childText(notifications, "SessionId"),
                childText(notifications, "EnterpriseUrl"),
                childText(notifications, "PartnerUrl"),
                parsed);
    }

    private SoapNotification parseNotification(Element notification) {
        Element sObject = firstChildByLocalName(notification, "sObject");
        if (sObject == null) {
            throw new SoapParseException("<Notification> without <sObject>");
        }
        return new SoapNotification(
                childText(notification, "Id"),        // Notification/Id (direct child)
                childText(sObject, "Id"),             // sObject/sf1:Id (direct child, local name Id)
                childText(sObject, "CLIENTID__c"),
                childText(sObject, "SVCNAME__c"),
                childText(sObject, "VERSION__c"),
                childText(sObject, "EXECMODE__c"),
                childText(sObject, "Request__c"));    // CDATA text (the inner JSON)
    }

    // --- DOM helpers (direct-child scoped, local-name matched) -------------------

    private static Document parseSecurely(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (SoapParseException e) {
            throw e;
        } catch (Exception e) {
            throw new SoapParseException("malformed SOAP XML: " + e.getMessage(), e);
        }
    }

    /** Text of the first direct child with this local name, or null. Trims. */
    private static String childText(Element parent, String localName) {
        Element c = firstChildByLocalName(parent, localName);
        if (c == null) {
            return null;
        }
        String text = c.getTextContent();
        return text == null ? null : text.trim();
    }

    private static Element firstChildByLocalName(Element parent, String localName) {
        List<Element> matches = directChildrenByLocalName(parent, localName);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static List<Element> directChildrenByLocalName(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && localName.equals(localName(k))) {
                out.add((Element) k);
            }
        }
        return out;
    }

    /** First element anywhere under root with this local name (breadth-first-ish). */
    private static Element firstDescendantByLocalName(Element root, String localName) {
        if (root == null) {
            return null;
        }
        if (localName.equals(localName(root))) {
            return root;
        }
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (localName.equals(localName(n))) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String localName(Node n) {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }
}
