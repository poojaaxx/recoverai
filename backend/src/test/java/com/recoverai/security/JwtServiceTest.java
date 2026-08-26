package com.recoverai.security;

import com.recoverai.domain.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level coverage of {@link JwtService} itself - encoding/decoding the
 * token, independent of the database-backed revocation check {@code
 * JwtAuthenticationFilter} layers on top (covered end-to-end in {@code
 * AuthenticationIntegrationTest} instead, since revocation only makes sense
 * as a whole-request behavior).
 */
@SpringBootTest
@ActiveProfiles("test")
class JwtServiceTest {

    @Autowired
    private JwtService jwtService;
    @Autowired
    private AuthProperties authProperties;

    @Test
    void issueToken_roundTrips_subjectRoleAndTokenVersion() {
        String token = jwtService.issueToken("alice", UserRole.MERCHANT_ADMIN, 3);

        Claims claims = jwtService.parseClaims(token).orElseThrow();
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(JwtService.roleClaim(claims)).isEqualTo("MERCHANT_ADMIN");
        assertThat(JwtService.tokenVersionClaim(claims)).isEqualTo(3);
    }

    @Test
    void issueToken_differentTokenVersions_produceDistinguishableClaims() {
        String v0 = jwtService.issueToken("bob", UserRole.OPERATOR, 0);
        String v1 = jwtService.issueToken("bob", UserRole.OPERATOR, 1);

        assertThat(JwtService.tokenVersionClaim(jwtService.parseClaims(v0).orElseThrow())).isEqualTo(0);
        assertThat(JwtService.tokenVersionClaim(jwtService.parseClaims(v1).orElseThrow())).isEqualTo(1);
    }

    @Test
    void parseClaims_malformedToken_returnsEmpty_neverThrows() {
        assertThat(jwtService.parseClaims("not-a-jwt-at-all")).isEmpty();
        assertThat(jwtService.parseClaims("")).isEmpty();
    }

    @Test
    void parseClaims_tamperedSignature_returnsEmpty() {
        String token = jwtService.issueToken("carol", UserRole.OPERATOR, 0);
        String tampered = token.substring(0, token.length() - 4) + "abcd";

        assertThat(jwtService.parseClaims(tampered)).isEmpty();
    }

    @Test
    void parseClaims_wrongSigningKey_returnsEmpty() {
        SecretKey wrongKey = Keys.hmacShaKeyFor("a-completely-different-signing-secret-not-the-real-one-0000000".getBytes(StandardCharsets.UTF_8));
        String tokenSignedWithWrongKey = Jwts.builder()
                .subject("mallory")
                .claim("role", "MERCHANT_ADMIN")
                .claim("tv", 0)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(wrongKey, Jwts.SIG.HS256)
                .compact();

        assertThat(jwtService.parseClaims(tokenSignedWithWrongKey)).isEmpty();
    }

    @Test
    void parseClaims_expiredToken_returnsEmpty() {
        // Built directly with the same real signing secret (read from AuthProperties, not
        // hardcoded) so the signature is genuinely valid - only the expiration is in the past.
        // JwtService.issueToken() has no way to produce an already-expired token by design, so
        // expiry has to be exercised this way rather than through the public API.
        SecretKey key = Keys.hmacShaKeyFor(authProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        String expiredToken = Jwts.builder()
                .subject("dave")
                .claim("role", "OPERATOR")
                .claim("tv", 0)
                .issuedAt(Date.from(past.minus(1, ChronoUnit.HOURS)))
                .expiration(Date.from(past))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        Optional<Claims> claims = jwtService.parseClaims(expiredToken);

        assertThat(claims).isEmpty();
    }

    @Test
    void expirationSeconds_matchesConfiguredMinutes() {
        assertThat(jwtService.expirationSeconds()).isEqualTo(authProperties.getJwtExpirationMinutes() * 60);
    }
}
