package com.idfcfirstbank.integration.capabilities.bureau.domain.service;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauFetchRequest;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauFetchResponse;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauQuery;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauResult;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.FetchStatus;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.in.FetchBureauData;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.out.CibilBureauPort;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.out.CommercialBureauPort;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.out.MultiBureauPort;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.out.ScorecardInfraPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * The Bureau capability core (the superset of the absorbed services' fetch logic,
 * built ONCE): for each requested {@link BureauType} it dispatches to that
 * bureau's OUT port IN PARALLEL, collects the canonical {@link BureauResult}s,
 * and merges them into one response. It is PURE — no scoring, no decisioning, no
 * cross-capability calls; it only fetches + returns normalized data.
 *
 * <p>Resilience: a single bureau failing does not fail the whole request — its
 * result is dropped and the overall {@link FetchStatus} becomes {@code PARTIAL}
 * (or {@code FAILED} if every requested bureau failed). This mirrors what the old
 * orchestrators did per-service, but shared.
 */
public class BureauService implements FetchBureauData {

    private static final Logger log = LoggerFactory.getLogger(BureauService.class);

    private final Map<BureauType, Function<BureauQuery, BureauResult>> dispatch;
    private final Executor executor;

    public BureauService(CibilBureauPort cibil, MultiBureauPort multiBureau,
                         CommercialBureauPort commercial, ScorecardInfraPort scorecardInfra,
                         Executor executor) {
        this.executor = executor;
        Map<BureauType, Function<BureauQuery, BureauResult>> map = new EnumMap<>(BureauType.class);
        map.put(BureauType.CIBIL, cibil::fetch);
        map.put(BureauType.MULTI_BUREAU, multiBureau::fetch);
        map.put(BureauType.COMMERCIAL, commercial::fetch);
        map.put(BureauType.BUREAU_SCORE, scorecardInfra::fetch);
        this.dispatch = Map.copyOf(map);
    }

    @Override
    public BureauFetchResponse fetch(BureauFetchRequest request) {
        List<BureauType> requested = request.bureauTypes().stream().distinct().toList();
        if (requested.isEmpty()) {
            return new BureauFetchResponse(List.of(), FetchStatus.FAILED, request.correlationId());
        }
        BureauQuery query = BureauQuery.from(request);

        // Fan out to each requested bureau in parallel; isolate per-bureau failures.
        List<CompletableFuture<BureauResult>> futures = new ArrayList<>();
        for (BureauType type : requested) {
            Function<BureauQuery, BureauResult> source = dispatch.get(type);
            futures.add(CompletableFuture
                    .supplyAsync(() -> source.apply(query), executor)
                    .exceptionally(ex -> {
                        // correlationId only (no PII) in logs.
                        log.warn("bureau.fetch-failed type={} correlationId={} cause={}",
                                type, request.correlationId(), ex.getMessage());
                        return null;
                    }));
        }

        List<BureauResult> results = new ArrayList<>();
        for (CompletableFuture<BureauResult> f : futures) {
            BureauResult r = f.join();
            if (r != null) {
                results.add(r);
            }
        }

        FetchStatus status = statusFor(requested.size(), results.size());
        log.info("bureau.fetch correlationId={} requested={} succeeded={} status={}",
                request.correlationId(), requested.size(), results.size(), status);
        return new BureauFetchResponse(List.copyOf(results), status, request.correlationId());
    }

    private static FetchStatus statusFor(int requested, int succeeded) {
        if (succeeded == 0) {
            return FetchStatus.FAILED;
        }
        return succeeded == requested ? FetchStatus.SUCCESS : FetchStatus.PARTIAL;
    }
}
