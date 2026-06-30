package com.idfcfirstbank.integration.brandrouter.domain;

/** Produce the routed XML to the ActiveMQ queue (mocked locally). */
public interface ActiveMqPort {
    void send(String xmlPayload);
}
