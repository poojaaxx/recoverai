package com.recoverai.controller;

import com.recoverai.domain.AppUser;
import com.recoverai.dto.LoginRequest;
import com.recoverai.dto.LoginResponse;
import com.recoverai.repository.AppUserRepository;
import com.recoverai.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * The one public authentication endpoint. Never logs a username, password,
 * or issued token (see {@link JwtService}). Returns the same generic 401 for
 * "no such user" and "wrong password" so a response can never be used to
 * enumerate valid usernames, and checks a password hash on every call
 * (including unknown usernames, against a dummy hash) so response timing
 * doesn't leak that distinction either.
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
        String token = jwtService.issueToken(appUser.getUsername(), appUser.getRole());
        return ResponseEntity.ok(new LoginResponse(token, "Bearer", appUser.getRole().name(), jwtService.expirationSeconds()));
    }
}
