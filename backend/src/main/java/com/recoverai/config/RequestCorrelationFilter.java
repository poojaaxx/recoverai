package com.recoverai.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Production readiness phase: assigns (or propagates, via {@code
 * X-Request-Id}) a request id, puts it in SLF4J's MDC so every log line
 * emitted while handling this request carries it (see {@code
 * logback-spring.xml}), echoes it back on the response, and logs one
 * structured line per request - method, path, status, and duration - once
 * the request completes. This is the one place HTTP status/latency
 * observability is captured; it never logs a request/response body, so it
 * can never leak an Authorization header, a webhook signature, or a
 * password.
 * <p>
 * {@code @Order(HIGHEST_PRECEDENCE)} so this filter's MDC context (and
 * timer) covers everything downstream, including Spring Security's own
 * filter chain - an unauthenticated 401 is just as observable as a 200.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("com.recoverai.http");
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = firstNonBlank(request.getHeader(REQUEST_ID_HEADER), UUID.randomUUID().toString());
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
            MDC.remove(MDC_KEY);
        }
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a.trim() : b;
    }
}
