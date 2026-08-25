package com.recoverai.controller;

import com.recoverai.dto.ObservabilityMetricsResponse;
import com.recoverai.execution.ObservabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Production readiness phase: policy/webhook/provider observability counts.
 * Read-only, available to any authenticated user (MERCHANT_ADMIN or
 * OPERATOR) - same authorization tier as {@code GET /api/recovery/metrics}.
 */
@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {

    private final ObservabilityService observabilityService;

    public ObservabilityController(ObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    @GetMapping("/metrics")
    public ObservabilityMetricsResponse metrics() {
        return observabilityService.getMetrics();
    }
}
