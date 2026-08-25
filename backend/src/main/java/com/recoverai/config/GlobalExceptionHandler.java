package com.recoverai.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Phase 10 hardening: a safety net for exceptions no controller already
 * handles locally. Spring's exception resolution always prefers a more
 * specific match, so every existing controller-local {@code
 * @ExceptionHandler} (e.g. {@code TransactionNotFoundException} ->
 * {@code 404}) continues to run exactly as before - this class only ever
 * catches what nothing else did.
 * <p>
 * Two goals, both purely defensive: (1) a malformed path variable (e.g. a
 * non-UUID transaction id) gets a response in this API's normal
 * {@code {"error": "..."}} shape instead of Spring Boot's default
 * {@code {timestamp,status,error,path}} body; (2) any genuinely
 * unexpected exception (a bug, not a validation failure) is guaranteed to
 * be logged server-side with full detail while the client only ever sees
 * a generic, safe message - never a stack trace, exception class name,
 * SQL, or internal path.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid value for request parameter: " + e.getName()));
    }

    /** A route that doesn't exist (e.g. the deliberately-absent /api/payments/execute) - stays a plain 404, not a caught "unexpected error". */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResourceFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "An unexpected error occurred. Please try again shortly."));
    }
}
