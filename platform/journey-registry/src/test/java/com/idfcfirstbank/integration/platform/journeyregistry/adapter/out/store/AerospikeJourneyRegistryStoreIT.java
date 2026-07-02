package com.idfcfirstbank.integration.platform.journeyregistry.adapter.out.store;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.policy.ClientPolicy;
import com.github.dockerjava.api.model.Ulimit;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.error.RegistryException;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.error.RegistryException.Kind;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyMeta;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyVersionRecord;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.VersionStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry's meta-pointer CAS against a REAL Aerospike: the single-editable
 * allocation and the checker-release election must hold under generation
 * contention, and version records must round-trip byte-for-byte (the stored
 * config IS what the engine will run). Tagged {@code integration}.
 */
@Tag("integration")
class AerospikeJourneyRegistryStoreIT {

    private static final String NAMESPACE = "test";

    private static GenericContainer<?> aerospike;
    private static IAerospikeClient client;
    private static AerospikeJourneyRegistryStore store;

    @BeforeAll
    static void start() {
        aerospike = newContainer();
        aerospike.start();
        client = connect();
        store = new AerospikeJourneyRegistryStore(client, NAMESPACE, "journey_meta", "journey_version");
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.close();
        }
        if (aerospike != null) {
            aerospike.stop();
        }
    }

    @Test
    void metaAndVersionRecordsRoundTrip() {
        store.create(JourneyMeta.created("rt-journey", "Round Trip", "PL", "loan", null));

        int allocated = store.allocateEditableVersion("rt-journey");
        assertThat(allocated).isEqualTo(1);

        JourneyVersionRecord draft = new JourneyVersionRecord(
                "rt-journey", 1, VersionStatus.DRAFT, "maker-1", null, "first",
                "{\"journeyKey\":\"rt-journey\",\"version\":1,\"nodes\":[]}",
                Instant.parse("2026-07-02T10:00:00Z"), Instant.parse("2026-07-02T10:05:00Z"));
        store.writeVersion(draft);

        JourneyVersionRecord read = store.version("rt-journey", 1).orElseThrow();
        assertThat(read).isEqualTo(draft); // byte-for-byte, timestamps included

        JourneyMeta meta = store.meta("rt-journey").orElseThrow();
        assertThat(meta.latestVersion()).isEqualTo(1);
        assertThat(meta.editableVersion()).isEqualTo(1);
        assertThat(meta.publishedVersion()).isZero();
        assertThat(store.versions("rt-journey")).containsExactly(draft);
    }

    @Test
    void duplicateCreateConflicts() {
        store.create(JourneyMeta.created("dup-journey", "Dup", null, null, null));
        assertThatThrownBy(() -> store.create(JourneyMeta.created("dup-journey", "Dup", null, null, null)))
                .isInstanceOfSatisfying(RegistryException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.CONFLICT));
    }

    @Test
    void concurrentAllocationsElectExactlyOneWinner() throws Exception {
        store.create(JourneyMeta.created("race-journey", "Race", null, null, null));

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                go.await();
                try {
                    store.allocateEditableVersion("race-journey");
                    won.incrementAndGet();
                } catch (RegistryException e) {
                    assertThat(e.kind()).isEqualTo(Kind.CONFLICT);
                    conflicted.incrementAndGet();
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(won.get()).as("generation CAS admits exactly one allocation").isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(threads - 1);
        assertThat(store.meta("race-journey").orElseThrow().editableVersion()).isEqualTo(1);
    }

    @Test
    void releaseElectionAdmitsExactlyOneCheckerAndMovesThePointer() throws Exception {
        store.create(JourneyMeta.created("rel-journey", "Rel", null, null, null));
        int version = store.allocateEditableVersion("rel-journey");

        int checkers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(checkers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        for (int i = 0; i < checkers; i++) {
            pool.submit(() -> {
                go.await();
                if (store.releaseEditable("rel-journey", version, version)) {
                    won.incrementAndGet();
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(won.get()).isEqualTo(1);
        JourneyMeta meta = store.meta("rel-journey").orElseThrow();
        assertThat(meta.editableVersion()).isZero();
        assertThat(meta.publishedVersion()).isEqualTo(version);

        // Released — the next allocation takes v2.
        assertThat(store.allocateEditableVersion("rel-journey")).isEqualTo(2);
    }

    @Test
    void releaseOfAStaleVersionIsRefused() {
        store.create(JourneyMeta.created("stale-journey", "Stale", null, null, null));
        store.allocateEditableVersion("stale-journey");

        assertThat(store.releaseEditable("stale-journey", 99, null)).isFalse();
        assertThat(store.meta("stale-journey").orElseThrow().editableVersion()).isEqualTo(1);
    }

    @Test
    void listScansAllJourneys() {
        store.create(JourneyMeta.created("list-a", "A", null, null, null));
        store.create(JourneyMeta.created("list-b", "B", null, null, null));

        List<String> keys = store.list().stream().map(JourneyMeta::key).toList();
        assertThat(keys).contains("list-a", "list-b");
        assertThat(keys).isSorted();
    }

    // ---- container plumbing (mirrors the engine's AerospikeTestSupport) ------------

    @SuppressWarnings("resource")
    private static GenericContainer<?> newContainer() {
        return new FixedPortAerospikeContainer()
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("aerospike-test.conf"),
                        "/etc/aerospike/aerospike.template.conf")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withUlimits(new Ulimit[]{new Ulimit("nofile", 4096L, 4096L)}))
                .waitingFor(Wait.forLogMessage(".*service ready.*", 1))
                .withStartupTimeout(Duration.ofSeconds(120));
    }

    private static final class FixedPortAerospikeContainer extends GenericContainer<FixedPortAerospikeContainer> {
        FixedPortAerospikeContainer() {
            super(DockerImageName.parse("aerospike/aerospike-server:7.2.0.1"));
            addFixedExposedPort(3000, 3000);
            addFixedExposedPort(3001, 3001);
            addFixedExposedPort(3002, 3002);
        }
    }

    private static IAerospikeClient connect() {
        ClientPolicy policy = new ClientPolicy();
        policy.timeout = 2000;
        RuntimeException last = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                IAerospikeClient candidate = new AerospikeClient(policy, "127.0.0.1", 3000);
                if (candidate.isConnected()) {
                    return candidate;
                }
                candidate.close();
            } catch (RuntimeException e) {
                last = e;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ie);
            }
        }
        throw new IllegalStateException("Aerospike did not become ready", last);
    }
}
