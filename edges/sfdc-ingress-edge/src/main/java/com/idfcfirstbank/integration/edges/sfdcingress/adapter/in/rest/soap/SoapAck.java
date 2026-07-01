package com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap;

/**
 * Builds the SOAP response SFDC expects for an Outbound Message. {@code <Ack>true}
 * means "durably accepted — stop resending"; {@code <Ack>false} (or a SOAP fault)
 * makes SFDC resend the ENTIRE batch (normalisation spec §5). Pure string builder;
 * no framework types.
 */
public final class SoapAck {

    private static final String NS = "http://soap.sforce.com/2005/09/outbound";

    private SoapAck() {
    }

    /** {@code <notificationsResponse><Ack>true|false</Ack></notificationsResponse>} in a SOAP envelope. */
    public static String ack(boolean accepted) {
        return """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                  <soapenv:Body>
                    <notificationsResponse xmlns="%s">
                      <Ack>%s</Ack>
                    </notificationsResponse>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(NS, accepted);
    }

    /** A SOAP fault for a whole-batch failure (unparseable envelope / auth). */
    public static String fault(String reason) {
        return """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                  <soapenv:Body>
                    <soapenv:Fault>
                      <faultcode>soapenv:Server</faultcode>
                      <faultstring>%s</faultstring>
                    </soapenv:Fault>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(escape(reason));
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
