package com.satdb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Точка входа Spring Boot. JavaFX запускается отдельно (см. JavaFxLauncher),
 * т.к. JavaFX требует собственную Application-нитку.
 */
@SpringBootApplication
@EnableCaching
public class SatelliteDbApplication {

    public static void main(String[] args) {
        SpringApplication.run(SatelliteDbApplication.class, args);
    }
}
