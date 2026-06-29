package com.idfcfirstbank.integration.capabilities.bureau.application;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauReportSet;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauRequest;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.CanonicalBureauResult;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.BureauVendorPort;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The fan-out/normalize core (framework-free): for each requested
 * {@link BureauType} it invokes that vendor's {@link BureauVendorPort} IN
 * PARALLEL, then merges the canonical results into a {@link BureauReportSet}.
 * This is the single place the bank pulls bureau data — replacing the 4-5x
 * duplicated bureau clients. Adding a bureau = registering one more port.
 */
public class BureauFetchService {

    private final Map<BureauType, BureauVendorPort> vendors;

    public BureauFetchService(List<BureauVendorPort> ports) {
        Map<BureauType, BureauVendorPort> map = new EnumMap<>(BureauType.class);
        for (BureauVendorPort port : ports) {
            map.put(port.type(), port);
        }
        this.vendors = map;
    }

    public BureauReportSet fetch(BureauRequest request) {
        // Fan out: one async pull per requested bureau that has a registered port.
        List<CompletableFuture<CanonicalBureauResult>> futures = request.bureauTypes().stream()
                .map(vendors::get)
                .filter(java.util.Objects::nonNull)
                .map(port -> CompletableFuture.supplyAsync(() -> port.fetch(request.identity())))
                .toList();

        // Join all; a vendor failure surfaces here (the capability maps it to ERROR).
        List<CanonicalBureauResult> results = futures.stream()
                .map(CompletableFuture::join)
                .sorted(Comparator.comparing(r -> r.type().name())) // deterministic merge order
                .toList();

        if (results.isEmpty()) {
            throw new IllegalStateException("no bureau vendor available for requested types " + request.bureauTypes());
        }
        return new BureauReportSet(results);
    }
}
