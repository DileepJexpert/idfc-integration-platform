package com.idfcfirstbank.integration.capabilities.verification.adapter.out.karza;

import com.idfcfirstbank.integration.capabilities.verification.domain.error.VerificationException;
import com.idfcfirstbank.integration.capabilities.verification.domain.model.AuthType;
import com.idfcfirstbank.integration.capabilities.verification.domain.model.ResolvedRoute;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.TokenProviderPort;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * Shared Karza HTTP client (OAuth Bearer POST + transport error classification), used by
 * every KARZA_* adapter — the per-svcName adapters differ only by svcName + endpoint, so
 * the call/classify logic lives here ONCE. Classification drives the retry engine (spec v2
 * §C): 4xx = PERMANENT, 5xx = TRANSIENT, read-timeout = AMBIGUOUS, connect-fail = TRANSIENT.
 * NO PII logged. Business declines are HTTP-200 bodies — NOT seen here (handled by the branch).
 *
 * <p>Explicit connect/read timeouts are REQUIRED: without them a hung Karza connection
 * blocks the consumer thread indefinitely (past {@code max.poll.interval.ms} → rebalance
 * storm) and the READ_TIMEOUT/AMBIGUOUS classification below could never fire.
 * {@link SimpleClientHttpRequestFactory} surfaces a read timeout as a
 * {@link java.net.SocketTimeoutException}, which is exactly what {@link #post} classifies.
 */
@Component
public class KarzaClient {

    /** Defaults: fail a hung call well under the Kafka poll interval. */
    static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    static final int DEFAULT_READ_TIMEOUT_MS = 20_000;

    private final TokenProviderPort tokens;
    private final RestClient restClient;

    /** Convenience (tests / callers not needing custom timeouts): uses the defaults. */
    public KarzaClient(TokenProviderPort tokens) {
        this(tokens, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    @Autowired
    public KarzaClient(TokenProviderPort tokens,
                       @Value("${idfc.verification.http.connect-timeout-ms:3000}") int connectTimeoutMs,
                       @Value("${idfc.verification.http.read-timeout-ms:20000}") int readTimeoutMs) {
        this.tokens = tokens;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> post(ResolvedRoute route, Map<String, Object> mappedRequest) {
        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri(URI.create(route.baseUrl()))
                    .contentType(MediaType.APPLICATION_JSON);
            if (route.authType() == AuthType.OAUTH_BEARER) {
                spec = spec.header("Authorization", "Bearer " + tokens.bearerToken(route.svcName()));
            }
            Map<String, Object> body = spec.body(mappedRequest).retrieve().body(Map.class);
            if (body == null) {
                throw new VerificationException(ErrorClass.PERMANENT, "EMPTY_RESPONSE", "karza returned empty body");
            }
            return body;
        } catch (RestClientResponseException e) {
            ErrorClass ec = e.getStatusCode().is4xxClientError() ? ErrorClass.PERMANENT : ErrorClass.TRANSIENT;
            throw new VerificationException(ec, "HTTP_" + e.getStatusCode().value(), "karza http error");
        } catch (RestClientException e) {
            // Covers ResourceAccessException (connect/IO) AND the base RestClientException
            // RestClient throws when a read timeout fires DURING response extraction. A
            // SocketTimeout => AMBIGUOUS (the vendor may have processed it); other I/O =>
            // TRANSIENT; anything else (e.g. an unexpected content type) => PERMANENT.
            if (causedBy(e, SocketTimeoutException.class)) {
                throw new VerificationException(ErrorClass.AMBIGUOUS, "READ_TIMEOUT", "karza read timeout");
            }
            if (causedBy(e, IOException.class)) {
                throw new VerificationException(ErrorClass.TRANSIENT, "IO", "karza unreachable");
            }
            throw new VerificationException(ErrorClass.PERMANENT, "RESPONSE", "karza response error");
        }
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
