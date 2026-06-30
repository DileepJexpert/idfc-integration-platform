package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.NodeType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ENGINE-SIDE CONTRACT LOCK (Schema Lock). Parses the EXACT JSON the DAG Designer
 * emits ({@code journeys/loan-origination.journey.json}) into a domain
 * {@link JourneyDefinition} and asserts the graph — the engine half of the
 * two-sided drift check. If the engine cannot load this file, the schema is
 * MISALIGNED — that is a co-lock decision, not a parser bend.
 */
class JourneyContractLoaderTest {

    private static final String CONTRACT = "journeys/loan-origination.journey.json";
    private final JourneyDefinitionLoader loader = new JourneyDefinitionLoader(new ObjectMapper());

    private JourneyDefinition load() {
        return loader.loadFromClasspath(CONTRACT);
    }

    @Test
    void parsesTopLevelShape() {
        JourneyDefinition def = load();
        assertThat(def.key()).isEqualTo("loan-origination");
        assertThat(def.startNodeId()).isEqualTo("n_customer");
        // §7 loan journey: customer, kyc, bureau, score, decide, book, done, reject.
        assertThat(def.nodes()).hasSize(8);
    }

    @Test
    void taskNodesCarryRealBackendCapabilityKeys() {
        JourneyDefinition def = load();
        assertThat(def.nodes().stream()
                .filter(n -> n.type() == NodeType.TASK)
                .map(JourneyNode::capability))
                .contains("customer-party", "kyc", "bureau", "scoring", "lending-origination")
                .doesNotContain("scoring-decisioning");
    }

    @Test
    void branchHasTwoArmsRoutingOnDecision() {
        JourneyNode decide = load().node("n_decide");
        assertThat(decide.type()).isEqualTo(NodeType.BRANCH);
        assertThat(decide.arms()).hasSize(1);
        assertThat(decide.arms().get(0).when()).isEqualTo("context.scoring.decision == 'APPROVED'");
        assertThat(decide.arms().get(0).next()).isEqualTo("n_book");
        assertThat(decide.defaultNext()).isEqualTo("n_reject");
    }

    @Test
    void bookingNodeIsMeteredWithCompensation() {
        JourneyNode book = load().node("n_book");
        assertThat(book.type()).isEqualTo(NodeType.TASK);
        assertThat(book.capability()).isEqualTo("lending-origination");
        assertThat(book.meterPool()).isEqualTo("finnone_pool");
        assertThat(book.compensation().operation()).isEqualTo("reverseBooking");
        assertThat(book.onFailure()).isEqualTo("compensate");
        assertThat(book.isMetered()).isTrue();
    }

    @Test
    void terminalNodesDeclareActionAndEmit() {
        JourneyNode done = load().node("n_done");
        assertThat(done.type()).isEqualTo(NodeType.TERMINAL);
        assertThat(done.action()).isEqualTo("push_decision_to_channel");
        assertThat(done.emit()).containsExactly("LoanBooked");
    }

    @Test
    void linearChainWiresCustomerThroughDecide() {
        JourneyDefinition def = load();
        assertThat(def.node("n_customer").successors()).containsExactly("n_kyc");
        assertThat(def.node("n_score").successors()).containsExactly("n_decide");
        assertThat(def.predecessorsOf("n_decide")).containsExactly("n_score");
    }
}
