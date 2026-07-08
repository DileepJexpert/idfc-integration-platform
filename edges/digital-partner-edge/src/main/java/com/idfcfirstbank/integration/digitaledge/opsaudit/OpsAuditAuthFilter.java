package com.idfcfirstbank.integration.digitaledge.opsaudit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Fail-closed auth for the edge's {@code /ops/*} surface — the same shape as the
 * journey ops filter: an {@code X-Ops-Token} that must equal the configured token AND
 * an {@code X-User-Id} actor header. Anything missing or wrong is a 401; a blank
 * configured token authorizes nothing (no blank-token bypass). Read-only surface, so
 * there is nothing to mutate even past the gate.
 */
public class OpsAuditAuthFilter extends OncePerRequestFilter {

    static final String TOKEN_HEADER = "X-Ops-Token";
    static final String ACTOR_HEADER = "X-User-Id";

    private final String expectedToken;

    public OpsAuditAuthFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(TOKEN_HEADER);
        String actor = request.getHeader(ACTOR_HEADER);
        boolean authed = expectedToken != null && !expectedToken.isBlank()
                && expectedToken.equals(token)
                && actor != null && !actor.isBlank();
        if (!authed) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"UNAUTHENTICATED\",\"message\":\"X-Ops-Token and X-User-Id are required\"}");
    }
}
