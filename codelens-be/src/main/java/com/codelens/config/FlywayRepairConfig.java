package com.codelens.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TEMPORARY: One-time Flyway repair to align schema-history checksums
 * after enabling Flyway and updating existing migrations.
 *
 * <p>This bean replaces the default Flyway migration strategy so that
 * {@code repair()} runs before {@code migrate()}, preventing validation
 * failures during application startup.</p>
 *
 * <p>Deploy with this bean once, verify the application starts, then
 * delete this class before the next deployment. Leaving it in place will
 * re-run {@code repair()} before every migration, which is harmless but
 * hides real migration validation failures.</p>
 */
@Configuration
public class FlywayRepairConfig {

    @Bean
    public FlywayMigrationStrategy repairAndMigrate() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
