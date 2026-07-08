package com.idfcfirstbank.integration.shared.sync;

import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
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
 * path, per the digital-lending contract. A plain POJO: the sync ingress app wires
 * it from the registered {@link SyncInvocable} beans.
 *
 * <p><b>Audit:</b> every call writes exactly one {@link SyncInvocation} via the
 * {@link SyncInvocationRecorder} — success, business "no", and technical failure
 * alike (the record is written in a finally-style path so a thrown technical error
 * still lands an audit row). This is why a money-movement sync call is auditable even
 * though it creates no journey run. The recorder is best-effort and never alters the
 * business result.
 */
public class SyncCapabilityInvoker {

    private final Map<String, SyncInvocable> byKey;
    private final SyncInvocationRecorder recorder;
    private final Clock clock;

    public SyncCapabilityInvoker(Collection<SyncInvocable> invocables) {
        this(invocables, SyncInvocationRecorder.NOOP, Clock.systemUTC());
    }

    public SyncCapabilityInvoker(Collection<SyncInvocable> invocables, SyncInvocationRecorder recorder) {
        this(invocables, recorder, Clock.systemUTC());
    }

    public SyncCapabilityInvoker(Collection<SyncInvocable> invocables, SyncInvocationRecorder recorder, Clock clock) {
        this.byKey = invocables.stream()
                .collect(Collectors.toUnmodifiableMap(SyncInvocable::capabilityKey, Function.identity()));
        this.recorder = recorder == null ? SyncInvocationRecorder.NOOP : recorder;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Map<String, Object> invoke(String capabilityKey, String operation,
                                      Map<String, Object> payload, SyncRequestContext context) {
        SyncInvocable invocable = byKey.get(capabilityKey);
        if (invocable == null) {
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "UNKNOWN_CAPABILITY",
                    "no sync capability registered for '" + capabilityKey + "'");
        }

        Instant startedAt = clock.instant();
        String invocationId = "sync-" + UUID.randomUUID();
        String idempotencyKey = invocable.idempotencyKeyOf(payload);
        String source = context == null ? null : context.source();
        String correlationId = context == null ? null : context.correlationId();

        try {
            Map<String, Object> response = invocable.invoke(operation, payload, context);
            recorder.record(new SyncInvocation(invocationId, capabilityKey, operation, source, idempotencyKey,
                    correlationId, invocable.downstreamRefOf(response), invocable.businessOutcome(response),
                    null, null, startedAt, durationMs(startedAt), false));
            return response;
        } catch (SyncTechnicalException e) {
            recorder.record(new SyncInvocation(invocationId, capabilityKey, operation, source, idempotencyKey,
                    correlationId, null, SyncOutcome.TECHNICAL_ERROR,
                    e.errorClass() == null ? null : e.errorClass().name(), e.code(),
                    startedAt, durationMs(startedAt), false));
            throw e;
        }
    }

    private long durationMs(Instant startedAt) {
        return Math.max(0, java.time.Duration.between(startedAt, clock.instant()).toMillis());
    }
}
