package com.idfcfirstbank.integration.capabilities.verification.adapter.out.karza;

import com.idfcfirstbank.integration.capabilities.verification.domain.model.ResolvedRoute;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationAdapter;
import org.springframework.stereotype.Component;

import java.util.Map;

/** ENT_KARZA_NEGATIVE_AREA_TAGGING adapter (Karza, OAuth) — thin over the shared {@link KarzaClient}. */
@Component
public class KarzaNegativeAreaAdapter implements VerificationAdapter {

    private final KarzaClient karza;

    public KarzaNegativeAreaAdapter(KarzaClient karza) {
        this.karza = karza;
    }

    @Override public String svcName() { return "ENT_KARZA_NEGATIVE_AREA_TAGGING"; }

    @Override public Map<String, Object> call(ResolvedRoute route, Map<String, Object> mappedRequest) {
        return karza.post(route, mappedRequest);
    }
}
