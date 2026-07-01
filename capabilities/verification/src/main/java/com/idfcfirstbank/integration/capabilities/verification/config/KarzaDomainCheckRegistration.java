package com.idfcfirstbank.integration.capabilities.verification.config;

import com.idfcfirstbank.integration.capabilities.verification.application.MapperPair;
import com.idfcfirstbank.integration.capabilities.verification.application.MapperRegistry;
import com.idfcfirstbank.integration.capabilities.verification.application.mapper.KarzaDomainCheckRequestMapper;
import com.idfcfirstbank.integration.capabilities.verification.application.mapper.KarzaResourceDataResponseMapper;
import org.springframework.context.annotation.Configuration;

/** Registers the KARZA_DOMAIN_CHECK mapper pair (config-as-data, spec v2 D.2). */
@Configuration
public class KarzaDomainCheckRegistration {
    public KarzaDomainCheckRegistration(MapperRegistry registry) {
        registry.register("KARZA_DOMAIN_CHECK",
                new MapperPair(new KarzaDomainCheckRequestMapper(), new KarzaResourceDataResponseMapper()));
    }
}
