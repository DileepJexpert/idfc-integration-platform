package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.adapter.out.http;

import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.config.SfdcUserManagementProperties;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.model.ResolvedSfdcTarget;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.model.SfdcAuthType;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.port.out.SfdcOrgPort;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import com.idfcfirstbank.integration.shared.sync.SyncTechnicalException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Map;

/**
 * REAL outbound HTTP to a resolved SFDC org (the mock-sfdc-org WireMocks in dev — only
 * the response DATA is mocked). ONE generic adapter reused across every svcName and org;
 * the target URL, auth type and token all come from the {@link ResolvedSfdcTarget}. The
 * caller BLOCKS, so connect+read timeouts are MANDATORY (a hung SFDC must fail fast, not
 * pin the thread).
 *
 * <h2>Read-vs-action classification (the posidex/imps safety line, encoded)</h2>
 * A 2xx body is returned as-is (data or a business "no"). A transport failure throws
 * {@link SyncTechnicalException} classified by {@link #httpClass}/{@link #transportClass}:
 * <ul>
 *   <li>4xx -&gt; PERMANENT (a bad request won't get better on retry).</li>
 *   <li>5xx / connect / IO -&gt; TRANSIENT (safe to retry).</li>
 *   <li>timeout -&gt; for a READ, TRANSIENT (a read is safe to repeat); for a WRITE,
 *       AMBIGUOUS (the mutation MAY have applied) — a write is only safely retried under
 *       the caller-supplied idempotency key (slice 2). Slice 1 exercises the read branch.</li>
 * </ul>
 */
@Component
public class SfdcOrgHttpClient implements SfdcOrgPort {

    private final RestClient http;

    public SfdcOrgHttpClient(SfdcUserManagementProperties props) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.connectTimeoutMs());
        rf.setReadTimeout(props.readTimeoutMs());
        this.http = RestClient.builder().requestFactory(rf).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(ResolvedSfdcTarget target, Map<String, Object> requestBody) {
        try {
            RestClient.RequestBodySpec spec = http.post()
                    .uri(URI.create(target.url()))
                    .contentType(MediaType.APPLICATION_JSON);
            if (target.authType() == SfdcAuthType.BEARER && target.authToken() != null && !target.authToken().isBlank()) {
                spec = spec.header("Authorization", "Bearer " + target.authToken());
            }
            Map<String, Object> body = spec.body(requestBody == null ? Map.of() : requestBody)
                    .retrieve().body(Map.class);
            if (body == null) {
                throw new SyncTechnicalException(ErrorClass.PERMANENT, "EMPTY_RESPONSE",
                        "SFDC org " + target.orgName() + " returned an empty body for svcName=" + target.svcName());
            }
            return body;
        } catch (RestClientResponseException e) {
            throw new SyncTechnicalException(httpClass(e.getStatusCode()),
                    "HTTP_" + e.getStatusCode().value(), "SFDC org http error");
        } catch (RestClientException e) {
            throw transportClass(e, target.write());
        }
    }

    /** 4xx is PERMANENT; 5xx is TRANSIENT (safe to retry — reads and idempotent writes alike). */
    private static ErrorClass httpClass(HttpStatusCode status) {
        return status.is4xxClientError() ? ErrorClass.PERMANENT : ErrorClass.TRANSIENT;
    }

    private static SyncTechnicalException transportClass(RestClientException e, boolean write) {
        if (causedBy(e, SocketTimeoutException.class)) {
            // Read: safe to repeat -> TRANSIENT. Write: the mutation MAY have applied ->
            // AMBIGUOUS; only the idempotency key (slice 2) makes the retry safe.
            ErrorClass ec = write ? ErrorClass.AMBIGUOUS : ErrorClass.TRANSIENT;
            return new SyncTechnicalException(ec, "READ_TIMEOUT", "SFDC org read timeout");
        }
        if (causedBy(e, IOException.class)) {
            return new SyncTechnicalException(ErrorClass.TRANSIENT, "IO", "SFDC org unreachable");
        }
        return new SyncTechnicalException(ErrorClass.PERMANENT, "RESPONSE", "SFDC org response error");
    }

    private static boolean causedBy(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }
}
