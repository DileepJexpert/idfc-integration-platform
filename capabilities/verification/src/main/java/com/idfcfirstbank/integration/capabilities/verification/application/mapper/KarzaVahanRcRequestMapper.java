package com.idfcfirstbank.integration.capabilities.verification.application.mapper;

import com.idfcfirstbank.integration.capabilities.verification.application.Mapper;
import com.idfcfirstbank.integration.capabilities.verification.application.MapperSupport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KARZA_VAHAN_RC request mapper (BRD B.1): context -> Karza request. Preserves the
 * ALT-FIELD-NAME tolerance ({@code registrationNumber} OR {@code reg_no}) and defaults
 * consent to "Y". Exact Karza request field names refine with open input D#3.
 */
public class KarzaVahanRcRequestMapper implements Mapper {
    @Override
    public Map<String, Object> map(Map<String, Object> in) {
        Object reg = MapperSupport.firstOf(in, "registrationNumber", "reg_no");
        Object consent = in == null ? null : in.getOrDefault("consent", "Y");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reg_no", reg == null ? "" : reg);
        out.put("consent", consent == null ? "Y" : consent);
        return out;
    }
}
