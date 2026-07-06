package com.idfcfirstbank.integration.capabilities.impsdisbursal.application;

import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.error.SyncTechnicalException;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model.SyncRequestContext;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The SYNC execution path, parallel to the async engine: dispatch
 * (capabilityKey, operation, payload, context) to the matching {@link SyncInvocable}
 * and return its mapped response IN-THREAD. There is NO journeyInstanceId, NO
 * Kafka request/response topic and NO engine state store on this path — the caller
 * is blocking for the answer.
 *
 * <p>Dispatch is by capabilityKey only. The partner ({@code source}) is carried on
 * the context for trace/authz but never selects a capability — one lane, one code
 * path, per the digital-lending contract.
 */
@Component
public class SyncCapabilityInvoker {

    private final Map<String, SyncInvocable> byKey;

    public SyncCapabilityInvoker(List<SyncInvocable> invocables) {
        this.byKey = invocables.stream()
                .collect(Collectors.toUnmodifiableMap(SyncInvocable::capabilityKey, Function.identity()));
    }

    public Map<String, Object> invoke(String capabilityKey, String operation,
                                      Map<String, Object> payload, SyncRequestContext context) {
        SyncInvocable invocable = byKey.get(capabilityKey);
        if (invocable == null) {
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "UNKNOWN_CAPABILITY",
                    "no sync capability registered for '" + capabilityKey + "'");
        }
        return invocable.invoke(operation, payload, context);
    }
}
