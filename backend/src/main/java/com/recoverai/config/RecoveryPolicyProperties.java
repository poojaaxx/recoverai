package com.recoverai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Deterministic thresholds for {@code RecoveryPolicyService} - the Phase 4
 * safety boundary. Every number here is an application-invented,
 * illustrative synthetic value for this prototype, configurable via
 * {@code application.yml}'s {@code recoverai.policy.*} rather than
 * hardcoded in the service. Defaults mirror {@code application.yml} so the
 * engine behaves sensibly even if that section is omitted.
 */
@Component
@ConfigurationProperties(prefix = "recoverai.policy")
@Getter
@Setter
public class RecoveryPolicyProperties {

    /** Prior RETRY_PAYMENT attempts allowed before a further retry is stopped. */
    private int maxAutomaticRetryAttempts = 2;

    /**
     * Above this transaction amount, autonomous recovery actions require
     * human approval instead of proceeding automatically. This doubles as
     * the "high-value approval threshold" - this prototype uses a single
     * configurable amount for both purposes rather than two knobs with
     * identical semantics.
     */
    private BigDecimal maxAutonomousRecoveryAmount = new BigDecimal("25000");

    /** Total recovery actions (any type, any outcome) allowed per transaction before recovery is stopped. */
    private int maxRecoveryActionsPerTransaction = 3;

    /** Lookback window, in hours, used by duplicate-action prevention. */
    private long duplicateActionWindowHours = 24;

    /**
     * P1.2 - minimum minutes required since the <i>most recent</i> recovery
     * action of any type before another autonomous action may run on the
     * same transaction. Distinct from {@link #duplicateActionWindowHours}:
     * duplicate-action prevention only blocks repeating the exact same
     * action; this is a general pacing rule across any two actions. {@code
     * 0} (the default) disables it entirely - kept off by default so it
     * never interferes with demo scenarios, which are meant to be usable
     * back-to-back.
     */
    private long minCooldownMinutesBetweenActions = 0;
}
