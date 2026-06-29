package com.idfcfirstbank.integration.orchestration.originationjourney.support;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.policy.ClientPolicy;
import com.github.dockerjava.api.model.Ulimit;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

/**
 * Boots a REAL single-node Aerospike via Testcontainers for the durable
 * journey-state round-trip IT (mirrors the edge's support). An in-memory fake
 * would not prove persistence across a real store.
 */
public final class AerospikeTestSupport {

    public static final String NAMESPACE = "test";
    public static final String INSTANCE_SET = "journey_instance";

    private AerospikeTestSupport() {
    }

    @SuppressWarnings("resource")
    public static GenericContainer<?> newContainer() {
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

    public static IAerospikeClient connect() {
        ClientPolicy policy = new ClientPolicy();
        policy.timeout = 2000;
        RuntimeException last = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                IAerospikeClient client = new AerospikeClient(policy, "127.0.0.1", 3000);
                if (client.isConnected()) {
                    return client;
                }
                client.close();
            } catch (RuntimeException e) {
                last = e;
            }
            sleep();
        }
        throw new IllegalStateException("Aerospike did not become ready", last);
    }

    private static void sleep() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
