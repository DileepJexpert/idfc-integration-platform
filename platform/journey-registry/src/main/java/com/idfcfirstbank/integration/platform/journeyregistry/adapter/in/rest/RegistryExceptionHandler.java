package com.idfcfirstbank.integration.platform.journeyregistry.adapter.in.rest;

import com.idfcfirstbank.integration.platform.journeyregistry.adapter.in.rest.RegistryDtos.ErrorBody;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.error.RegistryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@link RegistryException.Kind} -> HTTP status, one place. The body always has
 * the same shape ({@link ErrorBody}); {@code issues} is populated for 422s so
 * the designer's validation panel can render server findings directly.
 */
@RestControllerAdvice
public class RegistryExceptionHandler {

    @ExceptionHandler(RegistryException.class)
    public ResponseEntity<ErrorBody> handle(RegistryException e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;                    // 404
            case CONFLICT -> HttpStatus.CONFLICT;                      // 409
            case FORBIDDEN -> HttpStatus.FORBIDDEN;                    // 403 — maker-checker
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;           // 401 — no actor
            case VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY; // 422 — §7 gate
        };
        return ResponseEntity.status(status)
                .body(new ErrorBody(e.kind().name(), e.getMessage(), e.issues()));
    }
}
