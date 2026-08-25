package com.recoverai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 10 hardening: a lightweight, in-memory, per-client fixed-window
 * rate limiter for the handful of endpoints that do real, non-trivial
 * work per call - AI evaluation, batch risk analysis, and recovery
 * execution. Not a substitute for infrastructure-level rate limiting in a
 * real multi-instance deployment (a {@link ConcurrentHashMap} here is
 * necessarily per-instance and resets on restart) - appropriate for this
 * single-instance buildathon deployment specifically because it adds zero
 * new infrastructure. See {@link RateLimitProperties} for the bounds and
 * docs/ARCHITECTURE.md for the production recommendation.
 * <p>
 * {@code @Profile("!test")}: disabled in the {@code test} profile so it
 * can never interfere with any existing test's request volume (mirrors
 * {@code DemoSeedRunner}'s existing opt-out pattern) - covered instead by
 * a dedicated unit test that exercises this filter directly.
 */
@Component
@Profile("!test")
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Only endpoints that do real AI/risk/execution work are guarded - read-only GETs are not. */
    private static final Set<String> GUARDED_PATH_PREFIXES = Set.of(
            "/api/recovery-agent/evaluate",
            "/api/revenue-risk/analyze-all"
    );

    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

    private static final class Window {
        volatile long windowStartMillis;
        volatile int count;

        Window(long windowStartMillis) {
            this.windowStartMillis = windowStartMillis;
            this.count = 1;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean execute = "POST".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI() != null
                && request.getRequestURI().matches("/api/recovery/[^/]+/execute");
        boolean guarded = execute || (request.getRequestURI() != null
                && GUARDED_PATH_PREFIXES.stream().anyMatch(p -> request.getRequestURI().startsWith(p)));

        if (!properties.isEnabled() || !guarded) {
            chain.doFilter(request, response);
            return;
        }

        String clientKey = clientKey(request);
        if (isOverLimit(clientKey)) {
            log.warn("Rate limit exceeded for client {} on {}", clientKey, request.getRequestURI());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(properties.getWindowSeconds()));
            response.setContentType("application/json");
            response.getWriter().write(objectMapper.writeValueAsString(
                    Map.of("error", "Too many requests. Please slow down and try again shortly.")));
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isOverLimit(String clientKey) {
        long now = System.currentTimeMillis();
        long windowMillis = properties.getWindowSeconds() * 1000L;

        Window[] holder = new Window[1];
        windows.compute(clientKey, (key, existing) -> {
            if (existing == null || now - existing.windowStartMillis >= windowMillis) {
                Window fresh = new Window(now);
                holder[0] = fresh;
                return fresh;
            }
            existing.count++;
            holder[0] = existing;
            return existing;
        });

        return holder[0].count > properties.getRequestsPerWindow();
    }

    /** Render/most PaaS platforms sit behind a reverse proxy, so the real client IP is in X-Forwarded-For, not getRemoteAddr(). */
    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
