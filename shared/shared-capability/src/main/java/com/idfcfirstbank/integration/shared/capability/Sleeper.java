package com.idfcfirstbank.integration.shared.capability;

/** Injectable delay (real Thread.sleep in prod; a no-op/recorder in tests). */
@FunctionalInterface
public interface Sleeper {
    void sleep(long millis);

    Sleeper REAL = millis -> {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };
}
