package com.recoverai.controller;

import com.recoverai.domain.AppUser;
import com.recoverai.dto.LoginRequest;
import com.recoverai.dto.LoginResponse;
import com.recoverai.repository.AppUserRepository;
import com.recoverai.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Authentication endpoints. {@code /login} is the only public one; {@code
 * /refresh} and {@code /logout} both require an already-valid bearer token
 * (see {@code SecurityConfig}) and resolve the caller via the {@link
 * Authentication} Spring Security already populated from that token -
 * neither ever trusts a username supplied in the request body. Never logs a
 * username, password, or issued token (see {@link JwtService}). {@code
 * /login} returns the same generic 401 for "no such user" and "wrong
 * password" so a response can never be used to enumerate valid usernames,
 * and checks a password hash on every call (including unknown usernames,
 * against a dummy hash) so response timing doesn't leak that distinction
 * either.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** A syntactically valid bcrypt hash that matches no real password - used so an unknown username still pays the same hashing cost as a known one. */
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5wjJhTAGzu3P.qDS.ZOzz9c8m9G0i";

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Optional<AppUser> user = appUserRepository.findByUsername(request.username());
        String hashToCheck = user.map(AppUser::getPasswordHash).orElse(DUMMY_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

        if (user.isEmpty() || !passwordMatches) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password."));
        }

        AppUser appUser = user.get();
        String token = jwtService.issueToken(appUser.getUsername(), appUser.getRole(), appUser.getTokenVersion());
        return ResponseEntity.ok(new LoginResponse(token, "Bearer", appUser.getRole().name(), jwtService.expirationSeconds()));
    }

    /**
     * Issues a brand-new token for the caller's own account, from the
     * current database state (role, token version) - a sliding-session
     * refresh, not a separate long-lived refresh-token type. Only works
     * while the presented token is still valid: an expired token never
     * reaches this method authenticated in the first place ({@code
     * JwtAuthenticationFilter} rejects it before Spring MVC routing even
     * runs), so refreshing past expiry requires logging in again.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(Authentication authentication) {
        Optional<AppUser> user = appUserRepository.findByUsername(authentication.getName());
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Account no longer exists."));
        }

        AppUser appUser = user.get();
        String token = jwtService.issueToken(appUser.getUsername(), appUser.getRole(), appUser.getTokenVersion());
        return ResponseEntity.ok(new LoginResponse(token, "Bearer", appUser.getRole().name(), jwtService.expirationSeconds()));
    }

    /**
     * Revokes every token ever issued to the caller's account - including
     * the one used to call this endpoint - by incrementing {@code
     * AppUser.tokenVersion}. There is no per-token/per-session tracking, so
     * this is "log out everywhere" rather than "log out this one device";
     * see {@code AppUser.tokenVersion}'s javadoc for why that's the honest
     * scope of what a single per-user counter can express.
     */
    @PostMapping("/logout")
    @Transactional
    public ResponseEntity<?> logout(Authentication authentication) {
        Optional<AppUser> user = appUserRepository.findByUsername(authentication.getName());
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Account no longer exists."));
        }

        AppUser appUser = user.get();
        appUser.setTokenVersion(appUser.getTokenVersion() + 1);
        appUserRepository.save(appUser);
        return ResponseEntity.ok(Map.of("message", "Logged out. Every previously issued token for this account has been revoked."));
    }
}
