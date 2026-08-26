package com.recoverai.security;

import com.recoverai.domain.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and validates stateless HS256 JWTs for the application-level
 * authentication layer. A token embeds the {@code role} it was issued for
 * and the {@code tokenVersion} that was current on {@code AppUser} at
 * issuance time; {@code JwtAuthenticationFilter} is what actually enforces
 * that the embedded version still matches the user's current one (this
 * class only encodes/decodes the token itself and has no database
 * dependency). See {@code AppUser.tokenVersion}'s javadoc for the
 * revocation model. Never logs a token or the signing key.
 */
@Component
public class JwtService {

    private static final String ROLE_CLAIM = "role";
    private static final String TOKEN_VERSION_CLAIM = "tv";

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(AuthProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = properties.getJwtExpirationMinutes();
    }

    public String issueToken(String username, UserRole role, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim(ROLE_CLAIM, role.name())
                .claim(TOKEN_VERSION_CLAIM, tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationMinutes, ChronoUnit.MINUTES)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public long expirationSeconds() {
        return expirationMinutes * 60;
    }

    /** Returns empty for any malformed, expired, or invalidly-signed token - never throws. */
    public Optional<Claims> parseClaims(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static String roleClaim(Claims claims) {
        return claims.get(ROLE_CLAIM, String.class);
    }

    /** Null if the claim is absent (e.g. a token issued before this claim existed) - callers must treat that as "does not match" rather than skipping the check. */
    public static Integer tokenVersionClaim(Claims claims) {
        return claims.get(TOKEN_VERSION_CLAIM, Integer.class);
    }
}
