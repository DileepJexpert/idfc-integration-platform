package com.idfcfirstbank.integration.platform.journeyregistry.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.ValidationIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule-by-rule proof of the authoritative §7 validator, plus the parity check
 * that matters most: the engine's REAL shipped journey artifact validates clean
 * (fixture copied from origination-journey — if the schema drifts, this test is
 * the tripwire until the A5 CI lock lands).
 */
class JourneyConfigValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JourneyConfigValidator validator = new JourneyConfigValidator();

    private List<ValidationIssue> validate(String json) {
        try {
            return validator.validate(mapper.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> errorCodes(List<ValidationIssue> issues) {
        return issues.stream().filter(ValidationIssue::isError).map(ValidationIssue::code).toList();
    }

    // ---- the parity fixture ------------------------------------------------------

    @Test
    void theEnginesShippedJourneyValidatesClean() throws Exception {
        JsonNode real = mapper.readTree(
                getClass().getResourceAsStream("/loan-origination.parity.json"));
        List<ValidationIssue> issues = validator.validate(real);
        assertThat(errorCodes(issues)).as("real §7 artifact must pass: %s", issues).isEmpty();
    }

    // ---- A6: schemaVersion gate at authoring time ------------------------------------

    @Test
    void unknownSchemaVersionIsRejectedAtAuthoringTime() {
        List<ValidationIssue> issues = validate("""
                {"journeyKey":"t","version":1,"schemaVersion":3,"startNodeId":"end",
                 "nodes":[{"id":"end","type":"terminal","status":"completed"}]}
                """);
        assertThat(errorCodes(issues)).containsExactly("unsupportedSchemaVersion");
    }

    @Test
    void supportedAndLegacyUnstampedSchemaVersionsValidate() {
        String stamped = """
                {"journeyKey":"t","version":1,"schemaVersion":%d,"startNodeId":"end",
                 "nodes":[{"id":"end","type":"terminal","status":"completed"}]}
                """.formatted(JourneyConfigValidator.SUPPORTED_SCHEMA_VERSION);
        assertThat(errorCodes(validate(stamped))).isEmpty();
        String legacy = """
                {"journeyKey":"t","version":1,"startNodeId":"end",
                 "nodes":[{"id":"end","type":"terminal","status":"completed"}]}
                """;
        assertThat(errorCodes(validate(legacy))).isEmpty();
    }

    // ---- structural rules ----------------------------------------------------------

    @Test
    void emptyDagIsAnError() {
        assertThat(errorCodes(validate("{\"nodes\": []}"))).containsExactly("emptyDag");
        assertThat(errorCodes(validate("{}"))).containsExactly("emptyDag");
    }

    @Test
    void duplicateNodeIdsAreFlagged() {
        String json = """
                {"startNodeId": "a", "nodes": [
                  {"id": "a", "type": "terminal", "status": "completed"},
                  {"id": "a", "type": "terminal", "status": "completed"}
                ]}""";
        assertThat(errorCodes(validate(json))).contains("duplicateNodeId");
    }

    @Test
    void unknownNodeTypeFailsClosed() {
        String json = """
                {"startNodeId": "a", "nodes": [
                  {"id": "a", "type": "quantum-fork", "next": []}
                ]}""";
        assertThat(errorCodes(validate(json))).contains("unknownNodeType");
    }

    @Test
    void missingAndDanglingStartNode() {
        assertThat(errorCodes(validate("""
                {"nodes": [{"id": "a", "type": "terminal", "status": "completed"}]}""")))
                .contains("noStartNode");
        assertThat(errorCodes(validate("""
                {"startNodeId": "ghost",
                 "nodes": [{"id": "a", "type": "terminal", "status": "completed"}]}""")))
                .contains("startNodeMissing");
    }

    @Test
    void danglingEdgesIncludingOnFailureAreFlagged() {
        String json = """
                {"startNodeId": "a", "nodes": [
                  {"id": "a", "type": "task", "next": ["ghost"], "onFailure": "phantom"}
                ]}""";
        List<String> codes = errorCodes(validate(json));
        assertThat(codes.stream().filter("danglingEdge"::equals)).hasSize(2);
    }

    @Test
    void onFailurePolicyKeywordsAreNotEdges() {
        String json = """
                {"startNodeId": "a", "nodes": [
                  {"id": "a", "type": "task", "next": ["t"], "onFailure": "compensate"},
                  {"id": "t", "type": "terminal", "status": "completed"}
                ]}""";
        assertThat(errorCodes(validate(json))).isEmpty();
    }

    @Test
    void branchNeedsArmsAndADefault() {
        String json = """
                {"startNodeId": "b", "nodes": [
                  {"id": "b", "type": "branch"}
                ]}""";
        assertThat(errorCodes(validate(json)).stream().filter("branchNoDefault"::equals)).hasSize(2);
    }

    @Test
    void unknownTerminalStatusFailsClosed() {
        String json = """
                {"startNodeId": "t", "nodes": [
                  {"id": "t", "type": "terminal", "status": "approved-ish"}
                ]}""";
        assertThat(errorCodes(validate(json))).contains("invalidTerminalStatus");
    }

    @Test
    void unsupportedJoinPolicyFailsClosed() {
        String json = """
                {"startNodeId": "p", "nodes": [
                  {"id": "p", "type": "parallel", "branches": ["x", "y"]},
                  {"id": "x", "type": "task", "next": ["j"]},
                  {"id": "y", "type": "task", "next": ["j"]},
                  {"id": "j", "type": "join", "policy": "anyOf", "joinOn": ["x", "y"], "next": ["t"]},
                  {"id": "t", "type": "terminal", "status": "completed"}
                ]}""";
        assertThat(errorCodes(validate(json))).contains("unsupportedJoinPolicy");
    }

    @Test
    void joinMustNameExistingPredecessors() {
        String json = """
                {"startNodeId": "j", "nodes": [
                  {"id": "j", "type": "join", "policy": "allOf", "joinOn": ["ghost"], "next": ["t"]},
                  {"id": "t", "type": "terminal", "status": "completed"}
                ]}""";
        assertThat(errorCodes(validate(json))).contains("joinOnUnknownPredecessor");
    }

    // ---- reachability ---------------------------------------------------------------

    @Test
    void unreachableNodeIsAWarningNotAnError() {
        String json = """
                {"startNodeId": "a", "nodes": [
                  {"id": "a", "type": "task", "next": ["t"]},
                  {"id": "t", "type": "terminal", "status": "completed"},
                  {"id": "island", "type": "task", "next": ["t"]}
                ]}""";
        List<ValidationIssue> issues = validate(json);
        assertThat(errorCodes(issues)).isEmpty();
        assertThat(issues.stream().filter(i -> "unreachableNode".equals(i.code())).findFirst())
                .hasValueSatisfying(i -> assertThat(i.nodeId()).isEqualTo("island"));
    }

    @Test
    void aJourneyThatCannotReachATerminalIsAnError() {
        String json = """
                {"startNodeId": "a", "nodes": [
                  {"id": "a", "type": "task", "next": ["b"]},
                  {"id": "b", "type": "task", "next": ["a"]}
                ]}""";
        assertThat(errorCodes(validate(json))).contains("branchArmDeadEnd");
    }

    @Test
    void aFullParallelJoinGraphValidatesClean() {
        String json = """
                {"startNodeId": "p", "nodes": [
                  {"id": "p", "type": "parallel", "branches": ["x", "y"]},
                  {"id": "x", "type": "task", "capability": "kyc", "operation": "verify", "next": ["j"]},
                  {"id": "y", "type": "task", "capability": "bureau", "operation": "pull", "next": ["j"]},
                  {"id": "j", "type": "join", "policy": "allOf", "joinOn": ["x", "y"], "next": ["t"]},
                  {"id": "t", "type": "terminal", "status": "completed"}
                ]}""";
        assertThat(validate(json)).isEmpty();
    }
}
