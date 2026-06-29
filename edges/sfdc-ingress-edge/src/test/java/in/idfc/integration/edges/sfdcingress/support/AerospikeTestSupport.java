package in.idfc.integration.edges.sfdcingress.support;

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
 * Boots a REAL single-node Aerospike via Testcontainers for the concurrency
 * gate. An in-memory fake is explicitly NOT acceptable here — CREATE_ONLY
 * atomicity under concurrency is the thing under test, and a map would pass
 * falsely (punch list §D).
 *
 * <p>Uses a fixed 3000:3000 mapping + {@code access-address 127.0.0.1} in the
 * mounted config so the node advertises a host-reachable address.
 */
public final class AerospikeTestSupport {

    public static final String NAMESPACE = "test";
    public static final String RECORD_SET = "idem";
    public static final String APP_POINTER_SET = "idem_app";

    private AerospikeTestSupport() {
    }

    @SuppressWarnings("resource")
    public static GenericContainer<?> newContainer() {
        return new FixedPortAerospikeContainer()
                // Mounted as the image TEMPLATE: the entrypoint renders it (no ${}
                // substitutions) into aerospike.conf and starts asd automatically.
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("aerospike-test.conf"),
                        "/etc/aerospike/aerospike.template.conf")
                // Stay within the host hard nofile limit; proto-fd-max=1024 fits under this.
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withUlimits(new Ulimit[]{new Ulimit("nofile", 4096L, 4096L)}))
                .waitingFor(Wait.forLogMessage(".*service ready.*", 1))
                .withStartupTimeout(Duration.ofSeconds(120));
    }

    /** Fixed 3000/3001/3002 so the advertised {@code access-address:3000} resolves from the host. */
    private static final class FixedPortAerospikeContainer extends GenericContainer<FixedPortAerospikeContainer> {
        FixedPortAerospikeContainer() {
            super(DockerImageName.parse("aerospike/aerospike-server:7.2.0.1"));
            addFixedExposedPort(3000, 3000);
            addFixedExposedPort(3001, 3001);
            addFixedExposedPort(3002, 3002);
        }
    }

    /**
     * Connects with a retry loop: the listening port opens before the cluster is
     * query-ready, so we poll a trivial op until it succeeds.
     */
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
