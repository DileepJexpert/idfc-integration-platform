package com.idfcfirstbank.integration.capabilities.verification.application.mapper;

import com.idfcfirstbank.integration.capabilities.verification.application.Mapper;
import com.idfcfirstbank.integration.capabilities.verification.application.MapperSupport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KARZA_DOMAIN_CHECK request mapper (spec v2 D.2): context -> Karza request
 * {@code { organizationName, individualName, email, consent }}. Alt-field tolerant on
 * email ({@code email} OR {@code emailId}); consent defaults to Y.
 */
public class KarzaDomainCheckRequestMapper implements Mapper {
    @Override
    public Map<String, Object> map(Map<String, Object> in) {
        Object email = MapperSupport.firstOf(in, "email", "emailId");
        Object consent = in == null ? null : in.getOrDefault("consent", "Y");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("organizationName", in == null ? null : in.get("organizationName"));
        out.put("individualName", in == null ? null : in.get("individualName"));
        out.put("email", email);
        out.put("consent", consent == null ? "Y" : consent);
        return out;
    }
}
