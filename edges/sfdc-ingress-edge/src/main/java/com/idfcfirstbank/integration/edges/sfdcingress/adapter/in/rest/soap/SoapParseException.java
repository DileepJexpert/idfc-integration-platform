package com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap;

/**
 * The SOAP envelope itself could not be parsed (malformed XML, missing
 * {@code <notifications>}). This is a WHOLE-BATCH failure: the edge cannot even
 * un-batch, so it does NOT ACK — SFDC must resend the entire message (spec §5/§6).
 * Contrast with a single bad-CDATA notification, which is DLQ'd individually.
 */
public class SoapParseException extends RuntimeException {
    public SoapParseException(String message) {
        super(message);
    }

    public SoapParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
