package com.recoverai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A login identity for the application-level authentication layer (migration
 * V13). Deliberately minimal - no profile fields, no password reset flow -
 * this is a buildathon-appropriate identity store, not a full identity
 * platform. {@code passwordHash} is always a bcrypt hash ({@code
 * PasswordEncoderConfig}); the plaintext password is never persisted or
 * logged.
 * <p>
 * {@code tokenVersion} (migration V15) is the revocation mechanism: every
 * issued JWT embeds the token version that was current at issuance, and
 * {@code JwtAuthenticationFilter} rejects any token whose embedded version
 * doesn't match this row's current value. Logging out (or any future
 * "revoke all sessions" action) simply increments this column, which
 * instantly invalidates every previously issued token for this user -
 * there is no per-token/per-session tracking, only this one per-user
 * counter.
 */
@Entity
@Table(name = "app_users", uniqueConstraints = @UniqueConstraint(name = "uq_app_users_username", columnNames = "username"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserRole role;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private int tokenVersion = 0;
}
