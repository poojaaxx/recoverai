package com.recoverai.security;

import com.recoverai.domain.AppUser;
import com.recoverai.repository.AppUserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Reads a {@code Authorization: Bearer <jwt>} header and, if the token is
 * validly signed, unexpired, and its embedded {@code tokenVersion} still
 * matches the current value on the corresponding {@link AppUser} row,
 * populates the {@link SecurityContextHolder} with the username and a
 * single {@code ROLE_<role>} authority taken from the token's own claims.
 * <p>
 * The {@code tokenVersion} check is the revocation mechanism (see {@code
 * AppUser.tokenVersion}'s javadoc) and is why this filter now does one
 * indexed {@code findByUsername} lookup per authenticated request - a
 * deliberate, necessary tradeoff for genuine revocation to exist at all in
 * an otherwise-stateless JWT scheme; the role itself still comes only from
 * the token's own claims, never from the freshly-read database row, so a
 * compromised/stale row can't silently grant a different role than the one
 * the token was actually issued for. Not registered as a generic servlet
 * {@code @Component} filter - wired explicitly into the Spring Security
 * filter chain by {@link SecurityConfig} so it only ever runs once, in the
 * right position, and never as a duplicate app-wide filter.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AppUserRepository appUserRepository;

    public JwtAuthenticationFilter(JwtService jwtService, AppUserRepository appUserRepository) {
        this.jwtService = jwtService;
        this.appUserRepository = appUserRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            jwtService.parseClaims(token).ifPresent(claims -> authenticate(request, claims));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, Claims claims) {
        String username = claims.getSubject();
        String role = JwtService.roleClaim(claims);
        Integer tokenVersion = JwtService.tokenVersionClaim(claims);
        if (username == null || username.isBlank() || role == null || role.isBlank() || tokenVersion == null) {
            return;
        }

        Optional<AppUser> user;
        try {
            user = appUserRepository.findByUsername(username);
        } catch (Exception e) {
            // Fail closed, not open: a database error must never be treated as "this token is
            // fine" - the request proceeds unauthenticated (a clean 401) rather than either
            // granting access on unverifiable state or surfacing a raw 500 to the caller.
            log.warn("Token revocation check failed for a request; treating as unauthenticated: {}", e.toString());
            return;
        }
        if (user.isEmpty() || user.get().getTokenVersion() != tokenVersion) {
            return;
        }

        var authToken = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }
}
