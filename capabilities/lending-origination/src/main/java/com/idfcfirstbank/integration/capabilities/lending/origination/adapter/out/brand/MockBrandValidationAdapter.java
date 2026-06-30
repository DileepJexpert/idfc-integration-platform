package com.idfcfirstbank.integration.capabilities.lending.origination.adapter.out.brand;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.lending.origination.domain.port.BrandValidationPort;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CONFIG-AS-DATA brand validation (BRD §5): resolve the brand, load
 * {@code brand-config/{brand}.json}, (mock the brand API call), then apply the
 * config-driven {@code passLogic} rule ({@code fieldPath equals value -> pass=Y}).
 * Adding a brand is a NEW JSON FILE, not code. Real EntAuth/Kong + JOLT transforms
 * are a later step; here the device payload IS the (mock) brand response. A bad
 * brand/config throws — the service maps it to an ERROR response.
 */
public class MockBrandValidationAdapter implements BrandValidationPort {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> validate(String brand, Map<String, Object> devicePayload) {
        JsonNode config = loadConfig(brand);
        JsonNode passLogic = config.path("passLogic");
        String fieldPath = passLogic.path("fieldPath").asText(null);
        String expected = passLogic.path("equals").asText(null);

        Object actual = devicePayload == null ? null : devicePayload.get(fieldPath);
        boolean pass = expected != null && expected.equals(String.valueOf(actual));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("brand", config.path("brand").asText(brand));
        out.put("pass", pass ? "Y" : "N");
        out.put("rule", fieldPath + " == " + expected);
        return out;
    }

    private JsonNode loadConfig(String brand) {
        if (brand == null || brand.isBlank() || "null".equals(brand)) {
            throw new IllegalArgumentException("brand is required");
        }
        String resource = "brand-config/" + brand.toLowerCase() + ".json";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no brand config: " + resource);
            }
            return objectMapper.readTree(in);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("bad brand config: " + resource, e);
        }
    }
}
