package com.idfcfirstbank.integration.capabilities.bureau.adapter.in.rest;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauFetchResponse;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.in.FetchBureauData;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST IN adapter for the Bureau capability (Slice 2 invocation path; later the
 * orchestration engine drives it). Thin: map wire → domain, invoke the IN port,
 * return the canonical response. No business logic.
 */
@RestController
@RequestMapping("/api/v1/bureau")
public class BureauController {

    private final FetchBureauData fetchBureauData;

    public BureauController(FetchBureauData fetchBureauData) {
        this.fetchBureauData = fetchBureauData;
    }

    @PostMapping("/fetch")
    public BureauFetchResponse fetch(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationIdHeader,
            @RequestBody BureauFetchHttpRequest request) {
        String correlationId = correlationIdHeader != null && !correlationIdHeader.isBlank()
                ? correlationIdHeader
                : "bureau-" + UUID.randomUUID();
        return fetchBureauData.fetch(request.toDomain(correlationId));
    }
}
