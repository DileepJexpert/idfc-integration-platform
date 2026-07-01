package com.idfcfirstbank.integration.capabilities.verification.config;

import com.idfcfirstbank.integration.capabilities.verification.application.MapperPair;
import com.idfcfirstbank.integration.capabilities.verification.application.MapperRegistry;
import com.idfcfirstbank.integration.capabilities.verification.application.mapper.KarzaVahanRcRequestMapper;
import com.idfcfirstbank.integration.capabilities.verification.application.mapper.KarzaVahanRcResponseMapper;
import org.springframework.context.annotation.Configuration;

/** Registers the KARZA_VAHAN_RC mapper pair (config-as-data, BRD B.0). */
@Configuration
public class KarzaVahanRcRegistration {
    public KarzaVahanRcRegistration(MapperRegistry registry) {
        registry.register("KARZA_VAHAN_RC",
                new MapperPair(new KarzaVahanRcRequestMapper(), new KarzaVahanRcResponseMapper()));
    }
}
