package com.idfcfirstbank.integration.capabilities.verification.adapter.out.karza;

import com.idfcfirstbank.integration.capabilities.verification.domain.model.ResolvedRoute;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationAdapter;
import org.springframework.stereotype.Component;

import java.util.Map;

/** KARZA_DOMAIN_CHECK adapter (Karza, OAuth) — thin over the shared {@link KarzaClient}. */
@Component
public class KarzaDomainCheckAdapter implements VerificationAdapter {

    private final KarzaClient karza;

    public KarzaDomainCheckAdapter(KarzaClient karza) {
        this.karza = karza;
    }

    @Override public String svcName() { return "KARZA_DOMAIN_CHECK"; }

    @Override public Map<String, Object> call(ResolvedRoute route, Map<String, Object> mappedRequest) {
        return karza.post(route, mappedRequest);
    }
}
