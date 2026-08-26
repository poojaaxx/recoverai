package com.recoverai.demo;

import com.recoverai.config.RazorpayProperties;
import com.recoverai.seed.DemoDataSeeder;
import com.recoverai.seed.SeedReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Development/demo-only capability to restore the seeded demo dataset
 * (transactions, customers, revenue-risk rows, recovery attempts, webhook
 * events, and audit records) to its original deterministic state, so failure
 * -path testing can start from a clean slate without a full application
 * restart.
 * <p>
 * This is a thin wrapper - all the actual work is {@link
 * DemoDataSeeder#seed()}, the exact same deterministic wipe-and-regenerate
 * routine {@code DemoSeedRunner} already runs once at startup (see that
 * class's javadoc, which explicitly anticipates "a future {@code POST
 * /api/demo/reset} + reseed workflow"). No new deletion logic is introduced
 * here; {@code DemoDataSeeder.resetAll()} already deletes audit logs,
 * recovery attempts (which cascades to their {@code webhook_events} rows -
 * see migration V12), revenue-risk rows, transactions, customers, and the
 * merchant, in FK-safe order.
 * <p>
 * Never touches {@code AppUser} rows (login accounts) - {@code
 * AppUserSeeder.seedDemoUsers()} is a separate, idempotent upsert that this
 * class deliberately does not call, so resetting demo data can never log out
 * or invalidate the token of the admin who just called this endpoint.
 * <p>
 * Guarded exactly like {@link DemoConfirmationService} (P0.4), for the same
 * reasons:
 * <ol>
 *   <li>Only runs when {@code recoverai.demo.seed-enabled=true} - off by
 *       default in every profile including production, so this capability
 *       is never reachable in a real deployment.</li>
 *   <li>Refuses outright whenever {@code recoverai.razorpay.enabled=true} -
 *       if a real payment provider is active, wiping the database could
 *       destroy genuine in-flight state, so this path is disabled entirely
 *       rather than risk that.</li>
 * </ol>
 * Does not reset {@link com.recoverai.webhook.PaymentConfirmationService}'s
 * in-memory {@code invalidSignatureCount}/{@code malformedPayloadCount}
 * counters - those are already documented as a per-instance simplification
 * (see that class's javadoc) that naturally resets only on a real restart;
 * leaving them alone here keeps this class from reaching into
 * safety-relevant webhook-processing internals for a cosmetic metric.
 */
@Service
public class DemoResetService {

    private final DemoDataSeeder seeder;
    private final RazorpayProperties razorpayProperties;
    private final boolean demoSeedEnabled;

    public DemoResetService(DemoDataSeeder seeder, RazorpayProperties razorpayProperties,
                             @Value("${recoverai.demo.seed-enabled:false}") boolean demoSeedEnabled) {
        this.seeder = seeder;
        this.razorpayProperties = razorpayProperties;
        this.demoSeedEnabled = demoSeedEnabled;
    }

    public SeedReport reset() {
        if (!demoSeedEnabled) {
            throw new DemoResetNotAvailableException(
                    "Demo data reset is only available in a demo environment (DEMO_SEED_ENABLED=true).");
        }
        if (razorpayProperties.isEnabled()) {
            throw new DemoResetNotAvailableException(
                    "Demo data reset is disabled while a real payment provider is active.");
        }
        return seeder.seed();
    }
}
