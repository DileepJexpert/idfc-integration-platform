package com.idfcfirstbank.integration.capabilities.communications.adapter.out.commshub;

import com.idfcfirstbank.integration.capabilities.communications.domain.port.out.CommsHubPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mock of the bank's INTERNAL SHARED CommsHub (the real internal client is a later
 * slice). A brief fixed latency models CommsHub's processing time so a burst
 * genuinely overlaps under the {@code SendMeterPort} cap. PII SAFETY: it NEVER logs
 * the body (the OTP lives there) or the full mobile — only a masked number and the
 * body length.
 */
@Component
public class MockCommsHubAdapter implements CommsHubPort {

    private static final Logger log = LoggerFactory.getLogger(MockCommsHubAdapter.class);
    private static final long COMMSHUB_LATENCY_MILLIS = 20L;

    @Override
    public void sendSms(String toMobile, String body) {
        log.info("commshub.sms.sent to={} bodyLen={}", mask(toMobile), body == null ? 0 : body.length());
        sleepQuietly();
    }

    private static String mask(String mobile) {
        if (mobile == null || mobile.length() < 4) {
            return "****";
        }
        return "******" + mobile.substring(mobile.length() - 4);
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(COMMSHUB_LATENCY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
