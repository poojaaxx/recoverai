package com.recoverai.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code recoverai.auth.*} - see application.yml for defaults and
 * docs/ARCHITECTURE.md "Authentication & Authorization" for why
 * {@code jwtSecret} MUST be overridden via {@code AUTH_JWT_SECRET} in any
 * real deployment (the checked-in default is a documented, public,
 * local-dev-only value).
 */
@Component
@ConfigurationProperties(prefix = "recoverai.auth")
@Getter
@Setter
public class AuthProperties {

    private String jwtSecret;
    private long jwtExpirationMinutes = 480;

    private String demoAdminUsername = "merchant.admin";
    private String demoAdminPassword = "";
    private String demoOperatorUsername = "operator";
    private String demoOperatorPassword = "";
}
