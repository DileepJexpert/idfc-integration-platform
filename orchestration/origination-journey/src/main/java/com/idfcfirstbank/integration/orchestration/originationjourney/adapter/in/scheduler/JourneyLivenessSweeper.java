package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.in.scheduler;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.DecisionOutboundPort;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.JourneyInstanceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Liveness sweeper (Phase 1 item 5). The engine is choreographed and at-least-once,
 * but a lost/never-arriving capability response would otherwise leave a run RUNNING
 * forever — in the assisted model that breaks the external retry loop, because the
 * agent is never told the run ended. This scheduled sweeper fails-and-notifies any
 * RUNNING instance whose start is older than the run budget:
 * <ol>
 *   <li>publish an ERROR {@link JourneyDecision} (reaches SFDC via the decision
 *       topic → edge push-back — idempotent downstream, so a retry is safe);</li>
 *   <li>mark the instance FAILED and persist it (which also lets the durable store
 *       apply its terminal TTL).</li>
 * </ol>
 *
 * <p>Notify happens BEFORE the FAILED save so that if the notify cannot be
 * confirmed the instance stays RUNNING and the next sweep retries it — the agent is
 * never silently left waiting. Per-instance failures are isolated so one bad run
 * does not stall the sweep.
 */
@Component
@ConditionalOnProperty(name = "idfc.engine.liveness.enabled", havingValue = "true", matchIfMissing = true)
public class JourneyLivenessSweeper {

    /** Synthetic terminal-node id stamped on a timed-out run's decision. */
    static final String TIMEOUT_NODE_ID = "__timeout__";

    private static final Logger log = LoggerFactory.getLogger(JourneyLivenessSweeper.class);

    private final JourneyInstanceStore store;
    private final DecisionOutboundPort decisions;
    private final Clock clock;
    private final Duration runBudget;

    public JourneyLivenessSweeper(JourneyInstanceStore store, DecisionOutboundPort decisions, Clock clock,
                                  @Value("${idfc.engine.liveness.run-budget-seconds:900}") long runBudgetSeconds) {
        this.store = store;
        this.decisions = decisions;
        this.clock = clock;
        this.runBudget = Duration.ofSeconds(runBudgetSeconds);
    }

    @Scheduled(fixedDelayString = "${idfc.engine.liveness.sweep-interval-ms:60000}")
    public void sweep() {
        int failed = sweepStuckRuns();
        if (failed > 0) {
            log.warn("journey.liveness swept {} stuck run(s) to FAILED (budget={})", failed, runBudget);
        }
    }

    /**
     * Fail-and-notify every RUNNING instance whose start is older than the budget.
     * Returns the number successfully failed. Package-visible so it can be driven
     * deterministically in tests.
     */
    public int sweepStuckRuns() {
        Instant cutoff = clock.instant().minus(runBudget);
        List<JourneyInstance> stuck = store.findRunningStartedBefore(cutoff);
        int failed = 0;
        for (JourneyInstance instance : stuck) {
            try {
                // Notify first (idempotent downstream); only then mark terminal + persist.
                decisions.publish(timeoutDecision(instance));
                instance.fail();
                store.save(instance);
                failed++;
                log.warn("journey.liveness.timeout instanceId={} startedAt={} — RUNNING past budget {}",
                        instance.journeyInstanceId(), instance.startedAt(), runBudget);
            } catch (RuntimeException e) {
                // Leave it RUNNING so the next sweep retries; never silently drop the notify.
                log.warn("journey.liveness could not fail-and-notify instanceId={} — will retry next sweep",
                        instance.journeyInstanceId(), e);
            }
        }
        return failed;
    }

    private JourneyDecision timeoutDecision(JourneyInstance i) {
        return new JourneyDecision(
                i.journeyInstanceId(), i.correlationId(), i.applicationRef(),
                JourneyDecision.ERROR, null, TIMEOUT_NODE_ID, List.of(),
                payloadStr(i, "source"), payloadStr(i, "notificationId"), payloadStr(i, "sfdcRecordId"));
    }

    private static String payloadStr(JourneyInstance i, String key) {
        Object v = i.payload().get(key);
        return v == null ? null : String.valueOf(v);
    }
}
