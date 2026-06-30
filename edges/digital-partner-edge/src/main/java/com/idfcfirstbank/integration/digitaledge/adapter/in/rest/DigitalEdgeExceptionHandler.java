package com.idfcfirstbank.integration.digitaledge.adapter.in.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.Map;

/**
 * Platform-wide error tracking for the digital edge's REST surface. Any exception
 * that escapes a controller is logged here with its full stack trace and returned
 * as a consistent 500 body. Add-on for observability — no business logic.
 */
@RestControllerAdvice
public class DigitalEdgeExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DigitalEdgeExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUncaught(Exception ex, WebRequest request) {
        log.error("digital-edge.unhandled-exception path={} : {}", request.getDescription(false), ex.toString(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 500,
                "error", "Internal Server Error",
                "message", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
    }
}
