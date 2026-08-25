package com.recoverai.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Deployment-only, explicitly opt-in seeding of the demo dataset at
 * application startup — the deployment-phase equivalent of what tests
 * already do by calling {@link DemoDataSeeder#seed()} directly.
 * <p>
 * Off by default everywhere, including the default/production profile
 * (see {@code recoverai.demo.seed-enabled} in {@code application.yml}):
 * a deployed instance must not silently reseed itself on every restart.
 * Set {@code DEMO_SEED_ENABLED=true} only for the buildathon demo
 * deployment, where a populated database (the 5 named demo transactions
 * plus the bulk dataset) is the point.
 * <p>
 * Safe to enable on every restart of a demo environment specifically
 * because {@link DemoDataSeeder#seed()} is deterministic and idempotent —
 * it wipes and regenerates the exact same dataset every time, never
 * accumulates duplicate rows, and never touches anything this runner
 * doesn't call. Disabled in the {@code test} profile, since tests already
 * seed explicitly and deliberately, at whatever point in each test they
 * need to.
 */
@Component
@Profile("!test")
public class DemoSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);

    private final DemoDataSeeder seeder;
    private final AppUserSeeder appUserSeeder;
    private final boolean seedEnabled;

    public DemoSeedRunner(DemoDataSeeder seeder, AppUserSeeder appUserSeeder,
                           @Value("${recoverai.demo.seed-enabled:false}") boolean seedEnabled) {
        this.seeder = seeder;
        this.appUserSeeder = appUserSeeder;
        this.seedEnabled = seedEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            return;
        }
        log.info("DEMO_SEED_ENABLED=true - seeding the deterministic demo dataset now.");
        SeedReport report = seeder.seed();
        log.info("Demo dataset seeded: {} transactions, {} recovery attempts, {} audit log rows, {} named demo transactions.",
                report.totalTransactions(), report.recoveryAttemptCount(), report.auditLogCount(),
                report.demoTransactionIds().size());

        appUserSeeder.seedDemoUsers();
        log.info("Demo login accounts seeded (or left unseeded if no demo password env vars were set) - see docs/ARCHITECTURE.md.");
    }
}
