package com.idfcfirstbank.integration.platform.journeyregistry.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.platform.journeyregistry.adapter.out.store.InMemoryJourneyRegistryStore;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.error.RegistryException;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.error.RegistryException.Kind;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyMeta;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyVersionRecord;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.VersionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The maker-checker rules, proved against the service (the ONLY place they may
 * live — the designer's RoleGate is UX). The load-bearing assertions: author may
 * not approve their own version (FORBIDDEN -> 403), at most one editable draft
 * exists even under concurrency, submit is validation-gated, and the server —
 * not the client — owns the stored artifact's journeyKey/version identity.
 */
class RegistryServiceTest {

    private static final String KEY = "loan-origination";
    private static final String MAKER = "maker-asha";
    private static final String CHECKER = "checker-vikram";

    /** Minimal §7 config that passes the graph validator. */
    private static final String VALID_CONFIG = """
            {
              "journeyKey": "client-claims-another-key",
              "version": 999,
              "startNodeId": "n_start",
              "nodes": [
                {"id": "n_start", "type": "task", "capability": "kyc", "operation": "verify",
                 "next": ["n_done"]},
                {"id": "n_done", "type": "terminal", "status": "completed"}
              ]
            }""";

    /** Dangling edge -> validation error -> submit must refuse. */
    private static final String BROKEN_CONFIG = """
            {
              "startNodeId": "n_start",
              "nodes": [
                {"id": "n_start", "type": "task", "next": ["n_missing"]}
              ]
            }""";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RegistryService service;

    @BeforeEach
    void setUp() {
        service = new RegistryService(new InMemoryJourneyRegistryStore(), new JourneyConfigValidator(),
                objectMapper, Clock.fixed(Instant.parse("2026-07-02T10:00:00Z"), ZoneOffset.UTC));
        service.createJourney(KEY, "Loan Origination", "PL", null, null, MAKER);
    }

    // ---- identity + journey lifecycle -------------------------------------------

    @Test
    void duplicateJourneyKeyConflicts() {
        assertThatThrownBy(() -> service.createJourney(KEY, "again", null, null, null, MAKER))
                .isInstanceOfSatisfying(RegistryException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.CONFLICT));
    }

    @Test
    void journeyKeyMustBeKebabCase() {
        assertThatThrownBy(() -> service.createJourney("Bad Key!", "x", null, null, null, MAKER))
                .isInstanceOfSatisfying(RegistryException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.VALIDATION_FAILED));
    }

    @Test
    void mutatingCallsWithoutActorAreUnauthenticated() {
        assertThatThrownBy(() -> service.createJourney("another", "x", null, null, null, " "))
                .isInstanceOfSatisfying(RegistryException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.UNAUTHENTICATED));
        assertThatThrownBy(() -> service.createDraft(KEY, VALID_CONFIG, null, null))
                .isInstanceOfSatisfying(RegistryException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.UNAUTHENTICATED));
    }

    @Test
    void unknownJourneyIs404() {
        assertThatThrownBy(() -> service.createDraft("no-such", VALID_CONFIG, null, MAKER))
                .isInstanceOfSatisfying(RegistryException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.NOT_FOUND));
    }

    // ---- maker side ---------------------------------------------------------------

    @Test
    void serverStampsIdentityOverClientClaims() throws Exception {
        JourneyVersionRecord draft = service.createDraft(KEY, VALID_CONFIG, "first cut", MAKER);

        assertThat(draft.version()).isEqualTo(1);
        assertThat(draft.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(draft.authorId()).isEqualTo(MAKER);

        // The client claimed journeyKey/version — the SERVER's identity won.
        JsonNode stored = objectMapper.readTree(draft.configJson());
        assertThat(stored.get("journeyKey").asText()).isEqualTo(KEY);
        assertThat(stored.get("version").asInt()).isEqualTo(1);
    }

    @Test
    void secondDraftWhileOneIsOpenConflicts() {
        service.createDraft(KEY, VALID_CONFIG, null, MAKER);
        assertThatThrownBy(() -> service.createDraft(KEY, VALID_CONFIG, null, MAKER))
                .isInstanceOfSatisfying(RegistryException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.CONFLICT));
    }

    @Test
    void onlyADraftCanBeSaved() {
        JourneyVersionRecord draft = service.createDraft(KEY, VALID_CONFIG, null, MAKER);
        service.submit(KEY, draft.version(), MAKER);

        assertThatThrownBy(() -> service.saveDraft(KEY, draft.version(), VALID_CONFIG, null, MAKER))
                .isInstanceOfSatisfying(RegistryException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.CONFLICT));
    }

    @Test
    void submitIsValidationGated() {
        JourneyVersionRecord draft = service.createDraft(KEY, BROKEN_CONFIG, null, MAKER);

        assertThatThrownBy(() -> service.submit(KEY, draft.version(), MAKER))
                .isInstanceOfSatisfying(RegistryException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.VALIDATION_FAILED);
                    assertThat(e.issues()).isNotEmpty();
                    assertThat(e.issues().getFirst().code()).isEqualTo("danglingEdge");
                });
        // Refused — still an editable DRAFT, fixable in place.
        assertThat(service.version(KEY, draft.version()).status()).isEqualTo(VersionStatus.DRAFT);
    }

    // ---- checker side ---------------------------------------------------------------

    @Nested
    class WithPendingVersion {

        @BeforeEach
        void submitDraft() {
            JourneyVersionRecord draft = RegistryServiceTest.this.service
                    .createDraft(KEY, VALID_CONFIG, null, MAKER);
            RegistryServiceTest.this.service.submit(KEY, draft.version(), MAKER);
        }

        @Test
        void authorMayNotApproveTheirOwnVersion() {
            assertThatThrownBy(() -> service.approve(KEY, 1, MAKER))
                    .isInstanceOfSatisfying(RegistryException.class,
                            e -> assertThat(e.kind()).isEqualTo(Kind.FORBIDDEN));
            // Still pending — the 403 changed nothing.
            assertThat(service.version(KEY, 1).status()).isEqualTo(VersionStatus.PENDING_APPROVAL);
        }

        @Test
        void authorMayNotRejectTheirOwnVersionEither() {
            assertThatThrownBy(() -> service.reject(KEY, 1, "self-reject", MAKER))
                    .isInstanceOfSatisfying(RegistryException.class,
                            e -> assertThat(e.kind()).isEqualTo(Kind.FORBIDDEN));
        }

        @Test
        void approveByAnotherActorPublishesAndMovesThePointer() {
            JourneyVersionRecord published = service.approve(KEY, 1, CHECKER);

            assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
            assertThat(published.approverId()).isEqualTo(CHECKER);

            JourneyMeta meta = service.journey(KEY);
            assertThat(meta.publishedVersion()).isEqualTo(1);
            assertThat(meta.hasEditable()).as("editable slot released").isFalse();

            // The editable slot is free again — the next draft allocates v2.
            assertThat(service.createDraft(KEY, VALID_CONFIG, null, MAKER).version()).isEqualTo(2);
        }

        @Test
        void rejectReleasesTheSlotWithoutMovingThePublishedPointer() {
            JourneyVersionRecord rejected = service.reject(KEY, 1, "not like this", CHECKER);

            assertThat(rejected.status()).isEqualTo(VersionStatus.REJECTED);
            assertThat(rejected.note()).isEqualTo("not like this");

            JourneyMeta meta = service.journey(KEY);
            assertThat(meta.hasPublished()).isFalse();
            assertThat(meta.hasEditable()).isFalse();
            assertThat(service.createDraft(KEY, VALID_CONFIG, null, MAKER).version()).isEqualTo(2);
        }

        @Test
        void concurrentCheckerActionsElectExactlyOneWinner() throws Exception {
            int checkers = 8;
            ExecutorService pool = Executors.newFixedThreadPool(checkers);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger approved = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            AtomicInteger conflicted = new AtomicInteger();
            for (int i = 0; i < checkers; i++) {
                boolean approve = i % 2 == 0;
                String checker = "checker-" + i;
                pool.submit(() -> {
                    go.await();
                    try {
                        if (approve) {
                            service.approve(KEY, 1, checker);
                            approved.incrementAndGet();
                        } else {
                            service.reject(KEY, 1, "no", checker);
                            rejected.incrementAndGet();
                        }
                    } catch (RegistryException e) {
                        assertThat(e.kind()).isEqualTo(Kind.CONFLICT);
                        conflicted.incrementAndGet();
                    }
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

            // Exactly one checker won the release CAS; the record matches the winner.
            assertThat(approved.get() + rejected.get()).isEqualTo(1);
            assertThat(conflicted.get()).isEqualTo(checkers - 1);
            VersionStatus finalStatus = service.version(KEY, 1).status();
            assertThat(finalStatus).isEqualTo(
                    approved.get() == 1 ? VersionStatus.PUBLISHED : VersionStatus.REJECTED);
            assertThat(service.journey(KEY).publishedVersion())
                    .isEqualTo(approved.get() == 1 ? 1 : 0);
        }

        @Test
        void approveRequiresPendingStatus() {
            service.reject(KEY, 1, "no", CHECKER);
            assertThatThrownBy(() -> service.approve(KEY, 1, CHECKER))
                    .isInstanceOfSatisfying(RegistryException.class,
                            e -> assertThat(e.kind()).isEqualTo(Kind.CONFLICT));
        }
    }

    @Test
    void aDraftCannotBeApprovedBeforeSubmit() {
        service.createDraft(KEY, VALID_CONFIG, null, MAKER);
        assertThatThrownBy(() -> service.approve(KEY, 1, CHECKER))
                .isInstanceOfSatisfying(RegistryException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.CONFLICT));
    }

    // ---- single-editable-draft invariant under concurrency ---------------------------

    @Test
    void concurrentDraftCreatesAllocateExactlyOne() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                go.await();
                try {
                    service.createDraft(KEY, VALID_CONFIG, null, MAKER);
                    created.incrementAndGet();
                } catch (RegistryException e) {
                    assertThat(e.kind()).isEqualTo(Kind.CONFLICT);
                    conflicted.incrementAndGet();
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat(created.get()).as("single-editable-draft invariant").isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(threads - 1);
        assertThat(service.journey(KEY).editableVersion()).isEqualTo(1);
    }

    // ---- engine reads -------------------------------------------------------------

    @Test
    void publishedReadsServeCurrentAndPinnedHistoricalVersions() {
        // v1: draft -> submit -> publish
        service.createDraft(KEY, VALID_CONFIG, null, MAKER);
        service.submit(KEY, 1, MAKER);
        service.approve(KEY, 1, CHECKER);
        // v2: draft -> submit -> publish (pointer moves to 2)
        service.createDraft(KEY, VALID_CONFIG, null, MAKER);
        service.submit(KEY, 2, MAKER);
        service.approve(KEY, 2, CHECKER);

        List<JourneyVersionRecord> current = service.publishedConfigs();
        assertThat(current).hasSize(1);
        assertThat(current.getFirst().version()).as("bootstrap serves the pointer").isEqualTo(2);

        // Version pinning: an in-flight run started on v1 can STILL fetch v1.
        assertThat(service.publishedConfig(KEY, 1).version()).isEqualTo(1);
        assertThat(service.publishedConfig(KEY, 2).version()).isEqualTo(2);
    }

    @Test
    void neverPublishedVersionIsNotServedToTheEngine() {
        service.createDraft(KEY, VALID_CONFIG, null, MAKER);

        assertThat(service.publishedConfigs()).isEmpty();
        assertThatThrownBy(() -> service.publishedConfig(KEY, 1))
                .isInstanceOfSatisfying(RegistryException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.NOT_FOUND));
    }
}
