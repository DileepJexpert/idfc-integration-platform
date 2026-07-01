package com.idfcfirstbank.integration.capabilities.verification.application.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec v2 D.1 mappers: request adds version:1.0 (+ alt-field); response flattens the nested contract. */
class KarzaVahanRcMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KarzaVahanRcRequestMapper requestMapper = new KarzaVahanRcRequestMapper();
    private final KarzaVahanRcResponseMapper responseMapper = new KarzaVahanRcResponseMapper();

    @SuppressWarnings("unchecked")
    private Map<String, Object> fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/karza/" + name)) {
            assertThat(in).as(name).isNotNull();
            return objectMapper.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> innerResult(Map<String, Object> data) {
        return (Map<String, Object>) ((Map<String, Object>) ((List<Object>) data.get("result")).get(0)).get("result");
    }

    @Test
    void requestMapperAddsVersionAndToleratesBothRegistrationFields() {
        assertThat(requestMapper.map(Map.of("registrationNumber", "AB12CD1234", "consent", "Y")))
                .containsEntry("registrationNumber", "AB12CD1234").containsEntry("consent", "Y")
                .containsEntry("version", 1.0);
        assertThat(requestMapper.map(Map.of("reg_no", "XY99ZZ0000")))
                .containsEntry("registrationNumber", "XY99ZZ0000").containsEntry("version", 1.0);
    }

    @Test
    void passResponseExposesActiveAndClear() throws Exception {
        Map<String, Object> inner = innerResult(responseMapper.map(fixture("vahan-rc-pass.json")));
        assertThat(inner).containsEntry("rcStatus", "ACTIVE").containsEntry("blackListStatus", "CLEAR");
    }

    @Test
    void failResponseExposesABlacklistedStatus() throws Exception {
        Map<String, Object> inner = innerResult(responseMapper.map(fixture("vahan-rc-fail.json")));
        assertThat(inner).containsEntry("blackListStatus", "BLACKLIST");   // not CLEAR -> decline
    }
}
