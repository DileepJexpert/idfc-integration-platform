package com.idfcfirstbank.integration.edges.sfdcingress.config;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.policy.ClientPolicy;
import com.idfcfirstbank.integration.edges.sfdcingress.adapter.out.aerospike.AerospikeIdempotencyStore;
import com.idfcfirstbank.integration.edges.sfdcingress.application.DecisionService;
import com.idfcfirstbank.integration.edges.sfdcingress.application.DedupeService;
import com.idfcfirstbank.integration.edges.sfdcingress.application.EdgePolicies;
import com.idfcfirstbank.integration.edges.sfdcingress.application.Normalizer;
import com.idfcfirstbank.integration.edges.sfdcingress.application.SfdcIngressService;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.BlobStorePort;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.IdempotencyStorePort;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.MessagePublisherPort;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.OrgConfigPort;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.SfdcResponsePort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Wires the framework-free application services to their ports. The concrete
 * {@link AerospikeIdempotencyStore} is created here and exposed ONLY as
 * {@link IdempotencyStorePort}, so no caller couples to it (later extraction to
 * platform-idempotency is a move, not a rewrite).
 */
@Configuration
@EnableConfigurationProperties({EdgeProperties.class, AerospikeProperties.class})
public class EdgeBeanConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Supplier<String> transactionIdSupplier() {
        return () -> "txn-" + UUID.randomUUID();
    }

    @Bean
    EdgePolicies edgePolicies(EdgeProperties properties) {
        return new EdgePolicies(properties.poisonRedeliveryThreshold(), properties.maxJourneyRetry());
    }

    @Bean
    Normalizer normalizer(Supplier<String> transactionIdSupplier) {
        return new Normalizer(transactionIdSupplier);
    }

    @Bean
    DedupeService dedupeService(IdempotencyStorePort store, Clock clock) {
        return new DedupeService(store, clock);
    }

    @Bean
    SfdcIngressService sfdcIngressService(DedupeService dedupeService, IdempotencyStorePort store,
                                          OrgConfigPort orgConfig, BlobStorePort blobStore,
                                          MessagePublisherPort publisher, Normalizer normalizer,
                                          EdgePolicies policies, Clock clock) {
        return new SfdcIngressService(dedupeService, store, orgConfig, blobStore, publisher, normalizer, policies, clock);
    }

    @Bean
    DecisionService decisionService(IdempotencyStorePort store, SfdcResponsePort sfdcResponse) {
        return new DecisionService(store, sfdcResponse);
    }

    // --- Aerospike (real, local) -------------------------------------------------

    @Bean(destroyMethod = "close")
    IAerospikeClient aerospikeClient(AerospikeProperties props) {
        ClientPolicy policy = new ClientPolicy();
        // Don't hard-fail context startup if the cluster isn't up yet (e.g. tests
        // that exercise only the Kafka path). Store calls still require a live node.
        policy.failIfNotConnected = false;
        policy.timeout = 3000;
        return new AerospikeClient(policy, props.host(), props.port());
    }

    @Bean
    IdempotencyStorePort idempotencyStorePort(IAerospikeClient client, AerospikeProperties props, Clock clock) {
        return new AerospikeIdempotencyStore(client, props.namespace(), props.recordSet(),
                props.appPointerSet(), props.ttlSeconds(), clock);
    }
}
