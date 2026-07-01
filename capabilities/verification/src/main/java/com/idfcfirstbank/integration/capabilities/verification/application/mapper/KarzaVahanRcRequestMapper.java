package com.idfcfirstbank.integration.capabilities.verification.application.mapper;

import com.idfcfirstbank.integration.capabilities.verification.application.Mapper;
import com.idfcfirstbank.integration.capabilities.verification.application.MapperSupport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KARZA_VAHAN_RC request mapper (spec v2 D.1): context -> Karza request
 * {@code { registrationNumber, consent, version:1.0 }}. Preserves ALT-FIELD-NAME
 * tolerance ({@code registrationNumber} OR {@code reg_no}) and adds the fixed
 * {@code version:1.0} the Karza RC API expects.
 */
public class KarzaVahanRcRequestMapper implements Mapper {
    @Override
    public Map<String, Object> map(Map<String, Object> in) {
        Object reg = MapperSupport.firstOf(in, "registrationNumber", "reg_no");
        Object consent = in == null ? null : in.getOrDefault("consent", "Y");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("registrationNumber", reg == null ? "" : reg);
        out.put("consent", consent == null ? "Y" : consent);
        out.put("version", 1.0);
        return out;
    }
}
