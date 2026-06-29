package com.idfcfirstbank.integration.edges.sfdcingress.domain.exception;

/**
 * Unknown routing type or unknown org that is STILL absent after a config
 * refresh + recheck (punch list §E, C2). Provably permanent -> ACK + DLQ + alert.
 */
public class ConfigNotFoundException extends EdgeException {
    public ConfigNotFoundException(String message) {
        super(message, true);
    }
}
