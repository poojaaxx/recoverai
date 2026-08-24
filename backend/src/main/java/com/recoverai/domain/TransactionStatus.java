package com.recoverai.domain;

/**
 * Lifecycle states for a {@link Transaction}. Later phases (risk engine,
 * safety policy engine, recovery execution) transition transactions between
 * these states; this enum only defines the state space.
 */
public enum TransactionStatus {
    SUCCESS,
    FAILED,
    PENDING,
    ABANDONED,
    RECOVERED,
    ESCALATED,
    STOPPED
}
