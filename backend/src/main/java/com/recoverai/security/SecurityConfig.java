package com.recoverai.security;

import com.recoverai.repository.AppUserRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Production readiness phase: the one place that decides which endpoints
 * require authentication and which role an action requires. Deliberately
 * separate from every domain service - {@code RevenueRiskService},
 * {@code RecoveryPolicyService}, {@code RecoveryExecutionService}, and
 * {@code PaymentGateway} have no dependency on anything in this package and
 * no awareness that authentication exists, so this layer can never become
 * the thing that decides whether a recovery action is *safe* - only whether
 * the caller is *allowed to ask*. See docs/ARCHITECTURE.md "Authentication
 * & Authorization".
 * <p>
 * Public (no token required): {@code GET /api/health}, actuator health,
 * {@code POST /api/auth/login}, and {@code /api/webhooks/**} - the webhook
 * stays protected by its own independent HMAC signature check
 * ({@code RazorpayWebhookSignature}), not by user authentication, since the
 * caller is Razorpay's servers, not a logged-in user.
 * <p>
 * {@code POST /api/auth/refresh} and {@code POST /api/auth/logout}
 * deliberately are <b>not</b> in the public list above - both require a
 * currently valid (unexpired, unrevoked) bearer token, matching every other
 * authenticated endpoint, via the same {@code .anyRequest().authenticated()}
 * fallthrough rule.
 * <p>
 * Everything else under {@code /api/**} requires a valid bearer token.
 * {@code POST /api/recovery/{id}/execute}, {@code .../approve}, {@code
 * .../reject}, {@code POST /api/recovery/batch/execute}, and {@code POST
 * /api/demo/recovery/confirm-test-payment/{id}}
 * all additionally require the {@code MERCHANT_ADMIN} role - every one is a
 * write action with a real effect on transaction/attempt state (execute can
 * cause a real or simulated payment-gateway call; approve re-runs the full
 * safety pipeline and may too; reject and confirm-test-payment mutate audit
 * /confirmation state). Every read/analyze/recommend endpoint is available
 * to both {@code MERCHANT_ADMIN} and {@code OPERATOR}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtService jwtService;
    private final AppUserRepository appUserRepository;
    private final String allowedOrigins;

    public SecurityConfig(JwtService jwtService, AppUserRepository appUserRepository,
                           @Value("${recoverai.cors.allowed-origins}") String allowedOrigins) {
        this.jwtService = jwtService;
        this.appUserRepository = appUserRepository;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required."))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "You do not have permission to perform this action.")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/health", "/api/auth/login").permitAll()
                        .requestMatchers("/api/webhooks/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/recovery/*/execute").hasRole("MERCHANT_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/recovery/batch/execute").hasRole("MERCHANT_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/recovery/*/approve").hasRole("MERCHANT_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/recovery/*/reject").hasRole("MERCHANT_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/demo/recovery/confirm-test-payment/*").hasRole("MERCHANT_ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, appUserRepository), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Same allowed-origins property and same allowed methods/headers this app already used via WebMvcConfigurer - relocated here since Spring Security's filter chain now owns CORS for every request. */
    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private static void writeJsonError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
