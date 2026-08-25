package com.recoverai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 10 hardening: bounds on the handful of endpoints that trigger
 * real, non-trivial server work per call (AI evaluation, batch risk
 * analysis, payment execution) - see {@link RateLimitFilter}. Deliberately
 * a simple in-memory fixed-window counter, not a new infrastructure
 * dependency (Redis, a gateway, etc.) - appropriate for this single-
 * instance buildathon deployment; see docs/ARCHITECTURE.md for the
 * production recommendation if this were ever multi-instance.
 */
@Component
@ConfigurationProperties(prefix = "recoverai.rate-limit")
@Getter
@Setter
public class RateLimitProperties {

    /** Master on/off switch. */
    private boolean enabled = true;

    /** Requests allowed per client per window, across all guarded endpoints combined. */
    private int requestsPerWindow = 20;

    /** Fixed-window length, in seconds. */
    private long windowSeconds = 60;
}
