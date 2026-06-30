package com.idfcfirstbank.integration.capabilities.mandate.domain.model;

/**
 * The mandate registration transaction — the state this capability OWNS, keyed by
 * {@code invoiceNo} (the dedup key, BRD §3). Mutable status + the vendor
 * registration reference; updated by the vendor callback.
 */
public final class MandateTransaction {

    private final String invoiceNo;
    private final Vendor vendor;
    private String registrationRef;
    private MandateStatus status;

    public MandateTransaction(String invoiceNo, Vendor vendor, MandateStatus status) {
        this.invoiceNo = invoiceNo;
        this.vendor = vendor;
        this.status = status;
    }

    public String invoiceNo() { return invoiceNo; }
    public Vendor vendor() { return vendor; }
    public String registrationRef() { return registrationRef; }
    public MandateStatus status() { return status; }

    public void registered(String ref) { this.registrationRef = ref; }
    public void status(MandateStatus status) { this.status = status; }
}
