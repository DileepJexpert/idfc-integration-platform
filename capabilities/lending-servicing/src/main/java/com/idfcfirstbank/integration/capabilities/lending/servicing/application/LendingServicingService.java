package com.idfcfirstbank.integration.capabilities.lending.servicing.application;

import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.model.ClosureRecord;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.model.ClosureStatus;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.ClosureStorePort;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.CommHubPort;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.FinnOneForeclosurePort;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.MssfPort;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.SfdcCasePort;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.SfdcPartnerPaymentPort;
import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Servicing operations (BRD §4): closure/foreclosure processing that READS FinnOne
 * and WRITES SFDC cases — never books. Dedups on LAN+event so a redelivered event
 * never creates a duplicate case. All externals behind mocked ports.
 */
@Service
public class LendingServicingService {

    private final FinnOneForeclosurePort finnOne;
    private final SfdcCasePort sfdcCase;
    private final SfdcPartnerPaymentPort partnerPayment;
    private final CommHubPort commHub;
    private final MssfPort mssf;
    private final ClosureStorePort store;

    public LendingServicingService(FinnOneForeclosurePort finnOne, SfdcCasePort sfdcCase,
                                   SfdcPartnerPaymentPort partnerPayment, CommHubPort commHub,
                                   MssfPort mssf, ClosureStorePort store) {
        this.finnOne = finnOne;
        this.sfdcCase = sfdcCase;
        this.partnerPayment = partnerPayment;
        this.commHub = commHub;
        this.mssf = mssf;
        this.store = store;
    }

    public Map<String, Object> processMaturedLoan(CapabilityRequest req) {
        String lan = required(req, "lan");
        if (!dedup(lan, "matured")) {
            return out("lan", lan, "status", "MATURED", "duplicate", true);
        }
        store.save(new ClosureRecord(lan, "matured", ClosureStatus.MATURED, null));
        return out("lan", lan, "status", "MATURED");
    }

    public Map<String, Object> processClosedLoan(CapabilityRequest req) {
        String lan = required(req, "lan");
        if (!dedup(lan, "closed")) {
            return out("lan", lan, "duplicate", true);
        }
        String caseId = sfdcCase.createClosureCase(lan);
        store.save(new ClosureRecord(lan, "closed", ClosureStatus.SFDC_CREATED, caseId));
        return out("lan", lan, "status", "SFDC_CREATED", "sfdcCaseId", caseId);
    }

    public Map<String, Object> processExcessAmount(CapabilityRequest req) {
        String lan = required(req, "lan");
        boolean has = partnerPayment.hasPartnerPayment(lan);
        if (!has) {
            commHub.notify(lan, "excess amount with no partner payment");
        }
        return out("lan", lan, "partnerPayment", has, "notified", !has);
    }

    /** Batch foreclosure: read FinnOne (READ), validate foreclosureAmount<=0, create case. */
    public Map<String, Object> batchClosure(CapabilityRequest req) {
        String lan = required(req, "lan");
        double amount = finnOne.foreclosureAmount(lan);
        if (amount > 0) {
            store.save(new ClosureRecord(lan, "batch", ClosureStatus.VALIDATION_FAILED, null));
            return out("lan", lan, "status", "VALIDATION_FAILED", "foreclosureAmount", amount);
        }
        String caseId = sfdcCase.createClosureCase(lan);
        store.save(new ClosureRecord(lan, "batch", ClosureStatus.SFDC_CREATED, caseId));
        return out("lan", lan, "status", "SFDC_CREATED", "sfdcCaseId", caseId, "foreclosureAmount", amount);
    }

    /** Maruti loan/doc status via the MSSF adapter (BRD §4a). */
    public Map<String, Object> getMaruti(CapabilityRequest req) {
        String loanRef = required(req, "loanRef");
        String kind = String.valueOf(req.payload().getOrDefault("kind", "LOAN_STATUS"));
        return out("loanRef", loanRef, "kind", kind, "result", mssf.call(kind, loanRef));
    }

    private boolean dedup(String lan, String event) {
        return store.insertIfAbsent(new ClosureRecord(lan, event, ClosureStatus.MATURED, null));
    }

    private static String required(CapabilityRequest req, String field) {
        Object v = req.payload() == null ? null : req.payload().get(field);
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
