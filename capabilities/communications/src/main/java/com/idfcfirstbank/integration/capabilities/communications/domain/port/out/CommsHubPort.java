package com.idfcfirstbank.integration.capabilities.communications.domain.port.out;

/**
 * CommsHub — the bank's INTERNAL, SHARED communications service (SMS/OTP dispatch
 * used across the WHOLE bank). It is NOT an external vendor: it is a finite shared
 * resource, so every consumer must be a good neighbour and bound its call rate.
 * The backpressure itself lives in {@link SendMeterPort}, applied on the send path.
 */
public interface CommsHubPort {
    void sendSms(String toMobile, String body);
}
