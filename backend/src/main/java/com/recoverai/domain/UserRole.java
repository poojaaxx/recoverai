package com.recoverai.domain;

/**
 * The two application roles supported by the production-readiness
 * authentication layer. {@code MERCHANT_ADMIN} can do everything
 * {@code OPERATOR} can (read/analyze/recommend/evaluate) plus authorize
 * money-moving recovery execution; {@code OPERATOR} is read/analyze-only.
 * See {@code com.recoverai.security.SecurityConfig} for the enforced rule.
 */
public enum UserRole {
    MERCHANT_ADMIN,
    OPERATOR
}
