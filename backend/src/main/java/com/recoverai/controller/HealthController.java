package com.recoverai.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Lightweight application-level health/info endpoint, separate from Spring
 * Boot Actuator's /actuator/health, so the frontend has a stable, minimal
 * contract to check backend availability during the demo.
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "recoverai-backend",
                "timestamp", Instant.now().toString()
        );
    }
}
