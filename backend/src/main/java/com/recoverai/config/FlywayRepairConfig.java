package com.recoverai.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One-time recovery from a checksum mismatch on {@code V11}, caused by
 * editing that migration file after it had already been applied to the
 * production database (a mistake - Flyway migrations must never be
 * modified once applied; V12 exists specifically to carry the intended
 * fix forward instead). {@link Flyway#repair()} recalculates the
 * recorded checksum for already-applied migrations to match what is
 * currently resolved on disk, before {@link Flyway#migrate()} runs
 * normally - it does not re-run or alter any already-applied migration's
 * SQL, only the bookkeeping row that records its checksum.
 * <p>
 * <b>Remove this class</b> once a deploy has run it successfully at least
 * once (confirm via {@code GET /api/health}) - leaving it in place
 * permanently would silently paper over a genuine future checksum
 * mismatch instead of failing loudly, which is the protection Flyway
 * validation exists to provide.
 */
@Configuration
public class FlywayRepairConfig {

    @Bean
    public FlywayMigrationStrategy repairThenMigrateStrategy() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
