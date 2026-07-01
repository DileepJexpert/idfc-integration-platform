package com.idfcfirstbank.integration.capabilities.verification.application.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec v2 D.2/D.3: the generic Karza response mapper exposes the nested decision fields both branches read. */
class KarzaStep3MapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KarzaResourceDataResponseMapper responseMapper = new KarzaResourceDataResponseMapper();

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
    @SuppressWarnings("unchecked")
    void domainCheckPassExposesValidNonDisposableMatchedDomain() throws Exception {
        Map<String, Object> inner = innerResult(responseMapper.map(fixture("domain-check-pass.json")));
        assertThat(inner.get("result")).isEqualTo(true);
        assertThat(((Map<String, Object>) inner.get("data")).get("disposable")).isEqualTo(false);
        Map<String, Object> companyInfo = (Map<String, Object>)
                ((Map<String, Object>) inner.get("additional_info")).get("company_info");
        List<Object> orgDomainMatch = (List<Object>) companyInfo.get("org_domain_match");
        assertThat(((Map<String, Object>) orgDomainMatch.get(0)).get("match")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void domainCheckFailExposesDisposableTrue() throws Exception {
        Map<String, Object> inner = innerResult(responseMapper.map(fixture("domain-check-fail.json")));
        assertThat(((Map<String, Object>) inner.get("data")).get("disposable")).isEqualTo(true);   // -> decline
    }

    @Test
    void negativeAreaPassExposesIsNegativeFalse() throws Exception {
        assertThat(innerResult(responseMapper.map(fixture("negative-area-pass.json"))))
                .containsEntry("is_negative", false);
    }

    @Test
    void negativeAreaFailExposesIsNegativeTrue() throws Exception {
        assertThat(innerResult(responseMapper.map(fixture("negative-area-fail.json"))))
                .containsEntry("is_negative", true);   // -> decline
    }

    @Test
    void domainCheckRequestMapperIsAltFieldTolerantAndDefaultsConsent() {
        assertThat(new KarzaDomainCheckRequestMapper().map(Map.of("emailId", "a@b.com")))
                .containsEntry("email", "a@b.com").containsEntry("consent", "Y");
    }

    @Test
    void negativeAreaRequestMapperPassesThroughAndEnsuresConsent() {
        assertThat(new KarzaNegativeAreaRequestMapper().map(Map.of("addressId", "A1")))
                .containsEntry("addressId", "A1").containsEntry("consent", "Y");
    }
}
