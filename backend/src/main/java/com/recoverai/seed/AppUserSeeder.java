package com.recoverai.seed;

import com.recoverai.domain.AppUser;
import com.recoverai.domain.UserRole;
import com.recoverai.repository.AppUserRepository;
import com.recoverai.security.AuthProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Idempotent upsert of the two demo login accounts used to exercise the
 * authentication layer in the buildathon deployment - one {@code
 * MERCHANT_ADMIN}, one {@code OPERATOR}. Gated by the same {@code
 * DEMO_SEED_ENABLED} opt-in {@link DemoSeedRunner} already uses, since both
 * exist for the same reason: making a freshly deployed instance usable by a
 * judge without manual setup. See docs/ARCHITECTURE.md "Authentication &
 * Authorization" for why the default passwords are safe to commit (public,
 * documented, rotatable via {@code DEMO_ADMIN_PASSWORD}/{@code
 * DEMO_OPERATOR_PASSWORD}) rather than being real secrets.
 */
@Service
public class AppUserSeeder {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public AppUserSeeder(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder, AuthProperties properties) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    public void seedDemoUsers() {
        upsert(properties.getDemoAdminUsername(), properties.getDemoAdminPassword(), UserRole.MERCHANT_ADMIN);
        upsert(properties.getDemoOperatorUsername(), properties.getDemoOperatorPassword(), UserRole.OPERATOR);
    }

    private void upsert(String username, String password, UserRole role) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return;
        }
        String hash = passwordEncoder.encode(password);
        AppUser user = appUserRepository.findByUsername(username).orElseGet(AppUser::new);
        user.setUsername(username);
        user.setPasswordHash(hash);
        user.setRole(role);
        appUserRepository.save(user);
    }
}
