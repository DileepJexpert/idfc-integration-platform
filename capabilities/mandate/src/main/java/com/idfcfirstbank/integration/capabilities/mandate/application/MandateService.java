package com.idfcfirstbank.integration.capabilities.mandate.application;

import com.idfcfirstbank.integration.capabilities.mandate.domain.model.MandateStatus;
import com.idfcfirstbank.integration.capabilities.mandate.domain.model.MandateTransaction;
import com.idfcfirstbank.integration.capabilities.mandate.domain.model.Vendor;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.AutopayLinkPort;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.CbsNachPort;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.EnachVerificationPort;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.MandateEventPort;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.MandateStorePort;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.VendorMandatePort;
import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The mandate capability's operations (BRD §3). Framework-driven: each public
 * method is one {@code operation} and returns the output map that binds into the
 * journey context. Owns the mandate lifecycle state; dedups on {@code invoiceNo}
 * so a concurrent/redelivered register calls the vendor EXACTLY ONCE. Vendors are
 * behind ports (mocked locally).
 */
@Service
public class MandateService {

    private final VendorMandatePort vendorPort;
    private final EnachVerificationPort enachPort;
    private final AutopayLinkPort autopayPort;
    private final CbsNachPort cbsPort;
    private final MandateStorePort store;
    private final MandateEventPort events;

    public MandateService(VendorMandatePort vendorPort, EnachVerificationPort enachPort,
                          AutopayLinkPort autopayPort, CbsNachPort cbsPort,
                          MandateStorePort store, MandateEventPort events) {
        this.vendorPort = vendorPort;
        this.enachPort = enachPort;
        this.autopayPort = autopayPort;
        this.cbsPort = cbsPort;
        this.store = store;
        this.events = events;
    }

    /** register: encrypt(JWE, mocked) -> vendor via Kong -> persist pending. Idempotent on invoiceNo. */
    public Map<String, Object> register(CapabilityRequest req) {
        Map<String, Object> p = req.payload();
        String invoiceNo = required(p, "invoiceNo");
        Vendor vendor = vendorOf(p);

        MandateTransaction txn = new MandateTransaction(invoiceNo, vendor, MandateStatus.PENDING);
        if (!store.insertIfAbsent(txn)) {
            MandateTransaction existing = store.find(invoiceNo).orElseThrow();
            return out("invoiceNo", invoiceNo, "registrationRef", existing.registrationRef(),
                    "status", existing.status().name(), "duplicate", true);
        }
        String ref = vendorPort.register(vendor, invoiceNo, p); // only the winner calls the vendor
        txn.registered(ref);
        store.save(txn);
        return out("invoiceNo", invoiceNo, "registrationRef", ref, "status", "PENDING");
    }

    /** verifyEnach: build ENACH XML (mocked) -> ENACH/NPCI -> status. */
    public Map<String, Object> verifyEnach(CapabilityRequest req) {
        Map<String, Object> p = req.payload();
        String invoiceNo = required(p, "invoiceNo");
        String status = enachPort.verify(invoiceNo, p);
        return out("invoiceNo", invoiceNo, "enachStatus", status);
    }

    /** setupAutopayLink: QuickPay -> Dwarf -> SFDC-SMS (chained, mocked). */
    public Map<String, Object> setupAutopayLink(CapabilityRequest req) {
        Map<String, Object> p = req.payload();
        String invoiceNo = required(p, "invoiceNo");
        String link = autopayPort.setupAndSend(invoiceNo, p);
        return out("invoiceNo", invoiceNo, "autopayLink", link, "sent", true);
    }

    /** cancel: CBS EnquireNACHMandate -> if found, CBS CreateNACHMandate(cancel). */
    public Map<String, Object> cancel(CapabilityRequest req) {
        Map<String, Object> p = req.payload();
        String invoiceNo = required(p, "invoiceNo");
        boolean found = cbsPort.enquire(invoiceNo);
        if (found) {
            cbsPort.cancel(invoiceNo);
            store.find(invoiceNo).ifPresent(t -> { t.status(MandateStatus.FAILURE); store.save(t); });
        }
        return out("invoiceNo", invoiceNo, "found", found, "cancelled", found);
    }

    /** handleVendorCallback: decrypt (mocked) -> update state -> emit MandateCallback. */
    public Map<String, Object> handleVendorCallback(CapabilityRequest req) {
        Map<String, Object> p = req.payload();
        String invoiceNo = required(p, "invoiceNo");
        String result = String.valueOf(p.getOrDefault("status", "SUCCESS"));
        MandateStatus status = "SUCCESS".equalsIgnoreCase(result) ? MandateStatus.SUCCESS : MandateStatus.FAILURE;
        store.find(invoiceNo).ifPresent(t -> { t.status(status); store.save(t); });
        events.emitMandateCallback(invoiceNo,
                out("invoiceNo", invoiceNo, "status", status.name()));
        return out("invoiceNo", invoiceNo, "status", status.name(), "processed", true);
    }

    // ---- helpers -------------------------------------------------------------

    private static Vendor vendorOf(Map<String, Object> p) {
        Object v = p.get("vendor");
        try {
            return v == null ? Vendor.DIGIO : Vendor.valueOf(String.valueOf(v).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CapabilityException(ErrorClass.PERMANENT, "unknown vendor '" + v + "'");
        }
    }

    private static String required(Map<String, Object> p, String field) {
        Object v = p == null ? null : p.get(field);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new CapabilityException(ErrorClass.PERMANENT, field + " is required");
        }
        return String.valueOf(v);
    }

    private static Map<String, Object> out(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
