package com.idfcfirstbank.integration.capabilities.communications.domain.port.out;

/** Sends an SMS. Behind a port so the real gateway (later) swaps for the mock. */
public interface SmsSenderPort {
    void send(String toMobile, String body);
}
