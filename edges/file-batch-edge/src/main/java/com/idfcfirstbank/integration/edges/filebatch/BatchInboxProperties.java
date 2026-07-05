package com.idfcfirstbank.integration.edges.filebatch;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The DEMO file edge's config. {@code enabled} defaults FALSE — the poller is
 * demo scaffolding and must be switched on deliberately (demo profile/CLI),
 * never by classpath accident.
 */
@ConfigurationProperties("file-batch")
public record BatchInboxProperties(
        boolean enabled,
        String inboxDir,
        long pollMs,
        String originationTopic,
        String type,
        String orgId) {

    public BatchInboxProperties {
        inboxDir = (inboxDir == null || inboxDir.isBlank()) ? "demo/batch-inbox" : inboxDir;
        pollMs = pollMs <= 0 ? 2000 : pollMs;
        originationTopic = (originationTopic == null || originationTopic.isBlank())
                ? "orig.employee-lwd-update.v1" : originationTopic;
        type = (type == null || type.isBlank()) ? "EMPLOYEE_LWD_UPDATE" : type;
        orgId = (orgId == null || orgId.isBlank()) ? "HR-DEMO" : orgId;
    }
}
