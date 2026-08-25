package com.codelens.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TEMPORARY: One-time Flyway repair to align schema-history checksums
 * after enabling Flyway and updating existing migrations.
 *
 * <p>Deploy with this bean once, verify the application starts, then
 * delete this class before the next deployment. Leaving it in place will
 * re-run {@code repair()} on every startup, which is harmless but hides
 * real migration validation failures.
 */
@Configuration
public class FlywayRepairConfig {

    @Bean
    public CommandLineRunner repairFlyway(Flyway flyway) {
        return args -> flyway.repair();
    }
}
