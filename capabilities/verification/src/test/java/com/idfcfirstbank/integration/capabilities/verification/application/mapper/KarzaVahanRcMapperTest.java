package com.idfcfirstbank.integration.capabilities.verification.application.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** BRD B.1 mappers: alt-field request, and the raw Karza response flattened to the decision shape. */
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

    @Test
    void requestMapperToleratesBothRegistrationFieldNames() {
        assertThat(requestMapper.map(Map.of("registrationNumber", "AB12CD1234", "consent", "Y")))
                .containsEntry("reg_no", "AB12CD1234").containsEntry("consent", "Y");
        assertThat(requestMapper.map(Map.of("reg_no", "XY99ZZ0000")))
                .containsEntry("reg_no", "XY99ZZ0000").containsEntry("consent", "Y");   // consent defaults
    }

    @Test
    @SuppressWarnings("unchecked")
    void passResponseFlattensToActiveAndClear() throws Exception {
        Map<String, Object> data = responseMapper.map(fixture("vahan-rc-pass.json"));

        Map<String, Object> inner = (Map<String, Object>)
                ((Map<String, Object>) ((List<Object>) data.get("result")).get(0)).get("result");
        assertThat(inner).containsEntry("rcStatus", "ACTIVE");
        assertThat(inner).containsEntry("blackListStatus", "CLEAR");   // "NO" normalised to CLEAR
        assertThat(inner).containsEntry("registrationNumber", "AB12CD1234");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failResponseFlattensToABlacklistedStatus() throws Exception {
        Map<String, Object> data = responseMapper.map(fixture("vahan-rc-fail.json"));

        Map<String, Object> inner = (Map<String, Object>)
                ((Map<String, Object>) ((List<Object>) data.get("result")).get(0)).get("result");
        assertThat(inner).containsEntry("blackListStatus", "BLACKLISTED");   // not CLEAR -> decline
    }
}
