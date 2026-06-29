package com.idfcfirstbank.integration.digitaledge.support;

import com.idfcfirstbank.integration.digitaledge.domain.port.IdempotencyGatePort;

import java.util.concurrent.ConcurrentHashMap;

/** In-memory CREATE_ONLY gate mirroring the Aerospike adapter's atomic claims. */
public class InMemoryIdempotencyGate implements IdempotencyGatePort {

    private final ConcurrentHashMap<String, String> notifications = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> applications = new ConcurrentHashMap<>();

    @Override
    public boolean claimNotification(String notificationId) {
        return notifications.putIfAbsent(notificationId, notificationId) == null;
    }

    @Override
    public boolean claimApplication(String applicationKey, String ownerNotificationId) {
        return applications.putIfAbsent(applicationKey, ownerNotificationId) == null;
    }
}
