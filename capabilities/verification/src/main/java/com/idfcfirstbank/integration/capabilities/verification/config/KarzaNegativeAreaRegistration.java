package com.idfcfirstbank.integration.capabilities.verification.config;

import com.idfcfirstbank.integration.capabilities.verification.application.MapperPair;
import com.idfcfirstbank.integration.capabilities.verification.application.MapperRegistry;
import com.idfcfirstbank.integration.capabilities.verification.application.mapper.KarzaNegativeAreaRequestMapper;
import com.idfcfirstbank.integration.capabilities.verification.application.mapper.KarzaResourceDataResponseMapper;
import org.springframework.context.annotation.Configuration;

/** Registers the ENT_KARZA_NEGATIVE_AREA_TAGGING mapper pair (config-as-data, spec v2 D.3). */
@Configuration
public class KarzaNegativeAreaRegistration {
    public KarzaNegativeAreaRegistration(MapperRegistry registry) {
        registry.register("ENT_KARZA_NEGATIVE_AREA_TAGGING",
                new MapperPair(new KarzaNegativeAreaRequestMapper(), new KarzaResourceDataResponseMapper()));
    }
}
