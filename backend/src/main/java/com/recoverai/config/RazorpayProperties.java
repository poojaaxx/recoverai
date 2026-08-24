package com.recoverai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Razorpay payment-gateway configuration for Phase 6. Real calls require
 * <b>both</b> {@code enabled=true} and {@code mode=test} - two independent
 * opt-ins, deliberately redundant, so a real provider can never be
 * selected by accident from the default configuration. Credentials are
 * read only from environment variables (see {@code application.yml}) -
 * nothing here is ever a literal secret value.
 */
@Component
@ConfigurationProperties(prefix = "recoverai.razorpay")
@Getter
@Setter
public class RazorpayProperties {

    private boolean enabled = false;

    /** {@code simulation} (default, mock) or {@code test} (real Razorpay Test Mode API). */
    private String mode = "simulation";

    private String keyId = "";
    private String keySecret = "";
    private String webhookSecret = "";
    private String baseUrl = "https://api.razorpay.com";
    private int timeoutSeconds = 15;
}
