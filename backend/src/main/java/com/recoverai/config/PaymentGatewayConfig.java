package com.recoverai.config;

import com.recoverai.payment.MockPaymentGateway;
import com.recoverai.payment.PaymentGateway;
import com.recoverai.payment.RazorpayPaymentGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Selects the active {@link PaymentGateway}. Mirrors {@code
 * AIProviderConfig}'s pattern - one explicit factory method rather than
 * conditional-bean annotations, so there is exactly one obvious place
 * deciding which implementation backs the interface. {@link
 * RazorpayPaymentGateway} is selected only when <b>both</b> {@code
 * recoverai.razorpay.enabled=true} and {@code recoverai.razorpay.mode=test}
 * - two independent opt-ins - otherwise {@link MockPaymentGateway} is
 * always used, matching "default MUST remain safe for local development."
 */
@Configuration
public class PaymentGatewayConfig {

    @Bean
    public PaymentGateway paymentGateway(RazorpayProperties properties, WebClient.Builder webClientBuilder) {
        if (properties.isEnabled() && "test".equalsIgnoreCase(properties.getMode())) {
            return new RazorpayPaymentGateway(properties, webClientBuilder.build());
        }
        return new MockPaymentGateway();
    }
}
