package com.recoverai.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Phase 10 hardening: safe, non-breaking HTTP security headers on every
 * response. This is a pure API backend (JSON only, no HTML views except
 * Spring Boot's own generic error page for a route that doesn't exist),
 * so these headers cannot break any legitimate client - they only remove
 * capabilities a JSON API never needed in the first place (being framed,
 * MIME-sniffed, or cached).
 * <p>
 * {@code @Order(HIGHEST_PRECEDENCE)}: must run - and set its headers -
 * before any other filter that might short-circuit the chain (e.g.
 * {@link RateLimitFilter} returning 429 without calling further down the
 * chain), so every response carries these headers, including a blocked one.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "geolocation=(), camera=(), microphone=()");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        chain.doFilter(request, response);
    }
}
