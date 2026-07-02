package com.idfcfirstbank.integration.orchestration.originationjourney.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.in.kafka.OriginationConsumer;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store.InMemoryJourneyInstanceStore;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.error.UnroutableTypeException;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.ExpressionEvaluator;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.JourneyEngine;
import com.idfcfirstbank.integration.orchestration.originationjourney.support.FixedJourneySource;
import com.idfcfirstbank.integration.platform.messaging.PoisonMessageException;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A2 routing regression: {@code type -> journey} is EXPLICIT config data, and
 * unmapped is fail-closed. Before this, an unmapped type fell back to the
 * first-loaded journey — an account-creation event ran the loan-origination DAG.
 */
class TypeToJourneyRoutingTest {

    private static JourneyDefinition journey(String key, String capability) {
        JourneyNode work = JourneyNode.task("n_work", null, capability, "run", null,
                "context.work", null, null, null, false, List.of("n_done"));
        JourneyNode done = JourneyNode.terminal("n_done", "push_decision_to_channel",
                List.of(), "completed");
        return new JourneyDefinition(key, 1, "n_work", List.of(work, done));
    }

    private final JourneyDefinition origination = journey("loan-origination", "kyc");
    private final JourneyDefinition accountCreation = journey("account-creation", "customer-party");

    private final InMemoryJourneyInstanceStore store = new InMemoryJourneyInstanceStore();
    private final List<CapabilityRequest> requests = new CopyOnWriteArrayList<>();

    private JourneyOrchestrator orchestrator(Map<String, String> routes) {
        return new JourneyOrchestrator(
                new JourneyEngine(new ExpressionEvaluator()),
                FixedJourneySource.registry(routes, origination, accountCreation),
                store, requests::add, d -> { }, () -> "ji-random");
    }

    @Test
    void eachMappedTypeRunsItsOwnJourneyNeverSomeDefault() {
        JourneyOrchestrator orchestrator = orchestrator(Map.of(
                "PERSONAL_LOAN", "loan-origination",
                "ACCOUNT_CREATION", "account-creation"));

        String loanRun = orchestrator.onOrigination(Map.of(
                "type", "PERSONAL_LOAN", "correlationId", "corr-loan", "applicationRef", "A-1"));
        String accountRun = orchestrator.onOrigination(Map.of(
                "type", "ACCOUNT_CREATION", "correlationId", "corr-acct", "applicationRef", "A-2"));

        assertThat(store.find(loanRun).orElseThrow().journeyKey()).isEqualTo("loan-origination");
        assertThat(store.find(accountRun).orElseThrow().journeyKey())
                .as("account-creation must run ITS journey, not the origination DAG")
                .isEqualTo("account-creation");
        assertThat(requests).extracting(CapabilityRequest::capabilityKey)
                .containsExactly("kyc", "customer-party");
    }

    @Test
    void anUnmappedTypeFailsClosedAndStartsNothing() {
        // Only loans are mapped — exactly the pre-A2 account-creation situation.
        JourneyOrchestrator orchestrator = orchestrator(Map.of("PERSONAL_LOAN", "loan-origination"));

        assertThatThrownBy(() -> orchestrator.onOrigination(Map.of(
                "type", "ACCOUNT_CREATION", "correlationId", "corr-x", "applicationRef", "A-3")))
                .isInstanceOf(UnroutableTypeException.class)
                .hasMessageContaining("ACCOUNT_CREATION");

        assertThat(store.find("ji-corr-x")).as("no run may start for an unroutable type").isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    void addingAMappingRowIsAConfigChangeNotARebuild() {
        // Same journeys, same code — only the config map differs between these two.
        Map<String, String> before = Map.of("PERSONAL_LOAN", "loan-origination");
        assertThatThrownBy(() -> orchestrator(before).onOrigination(Map.of(
                "type", "ACCOUNT_CREATION", "correlationId", "corr-b", "applicationRef", "A-4")))
                .isInstanceOf(UnroutableTypeException.class);

        Map<String, String> after = new HashMap<>(before);
        after.put("ACCOUNT_CREATION", "account-creation"); // the config row

        String run = orchestrator(after).onOrigination(Map.of(
                "type", "ACCOUNT_CREATION", "correlationId", "corr-c", "applicationRef", "A-5"));
        assertThat(store.find(run).orElseThrow().journeyKey()).isEqualTo("account-creation");
    }

    @Test
    void aMappingToAnUnpublishedJourneyAlsoFailsClosed() {
        JourneyOrchestrator orchestrator = orchestrator(Map.of("PERSONAL_LOAN", "ghost-journey"));

        assertThatThrownBy(() -> orchestrator.onOrigination(Map.of(
                "type", "PERSONAL_LOAN", "correlationId", "corr-g", "applicationRef", "A-6")))
                .isInstanceOf(UnroutableTypeException.class)
                .hasMessageContaining("ghost-journey");
    }

    @Test
    void theKafkaAdapterClassifiesUnroutableAsPoisonStraightToTheDlq() throws Exception {
        JourneyOrchestrator failing = mock(JourneyOrchestrator.class);
        when(failing.onOrigination(Map.of("type", "ACCOUNT_CREATION")))
                .thenThrow(new UnroutableTypeException("no route for ACCOUNT_CREATION"));
        OriginationConsumer consumer = new OriginationConsumer(failing, new ObjectMapper());

        assertThatThrownBy(() -> consumer.onMessage("{\"type\": \"ACCOUNT_CREATION\"}"))
                .as("retrying cannot conjure a mapping — poison, not retry")
                .isInstanceOf(PoisonMessageException.class)
                .hasCauseInstanceOf(UnroutableTypeException.class);
    }
}
