package com.idfcfirstbank.integration.capabilities.verification.application.mapper;

import com.idfcfirstbank.integration.capabilities.verification.application.Mapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ENT_KARZA_NEGATIVE_AREA_TAGGING request mapper (spec v2 D.3): pass the address context
 * through to Karza, ensuring consent. Full request field list refines with open input F#5.
 */
public class KarzaNegativeAreaRequestMapper implements Mapper {
    @Override
    public Map<String, Object> map(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>(in == null ? Map.of() : in);
        out.putIfAbsent("consent", "Y");
        return out;
    }
}
