package com.idfcfirstbank.integration.capabilities.communications.adapter.out.sms;

import com.idfcfirstbank.integration.capabilities.communications.domain.port.out.SmsSenderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mock SMS gateway (the real provider is a later slice). PII SAFETY: it NEVER logs
 * the body (the OTP lives there) or the full mobile — only a masked number and the
 * body length, enough to prove a send happened without persisting a secret.
 */
@Component
public class MockSmsSenderAdapter implements SmsSenderPort {

    private static final Logger log = LoggerFactory.getLogger(MockSmsSenderAdapter.class);

    @Override
    public void send(String toMobile, String body) {
        log.info("comm.sms.sent to={} bodyLen={}", mask(toMobile), body == null ? 0 : body.length());
    }

    private static String mask(String mobile) {
        if (mobile == null || mobile.length() < 4) {
            return "****";
        }
        return "******" + mobile.substring(mobile.length() - 4);
    }
}
