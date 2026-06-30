package com.idfcfirstbank.integration.brandrouter;

import com.idfcfirstbank.integration.brandrouter.application.BrandRouterService;
import com.idfcfirstbank.integration.brandrouter.domain.RoutingDecision;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BrandRouterServiceTest {

    private final BrandRouterService router = new BrandRouterService();
    private final Set<String> partitions = Set.of("GODREJ", "BOSCH", "TCL");

    @Test
    void partitionedBrandRoutesToKafkaKeyedByBrand() {
        RoutingDecision d = router.route("GODREJ", "{\"x\":1}", partitions);
        assertThat(d.target()).isEqualTo(RoutingDecision.Target.KAFKA);
        assertThat(d.key()).isEqualTo("GODREJ");
        assertThat(d.payload()).isEqualTo("{\"x\":1}");
    }

    @Test
    void nonPartitionedBrandConvertsToXmlForActiveMq() {
        RoutingDecision d = router.route("WHIRLPOOL", "{\"x\":1}", partitions);
        assertThat(d.target()).isEqualTo(RoutingDecision.Target.ACTIVEMQ);
        assertThat(d.payload()).contains("<ActivemqRequest>").contains("<brand>WHIRLPOOL</brand>");
    }

    @Test
    void unknownBrandFallsToActiveMq() {
        assertThat(router.route(null, "{}", partitions).target())
                .isEqualTo(RoutingDecision.Target.ACTIVEMQ);
    }
}
