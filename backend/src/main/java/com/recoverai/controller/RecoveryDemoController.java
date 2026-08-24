package com.recoverai.controller;

import com.recoverai.demo.DemoScenarioNotFoundException;
import com.recoverai.demo.RecoveryDemoService;
import com.recoverai.dto.RecoveryDemoScenarioResponse;
import com.recoverai.dto.RecoveryDemoSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Phase 8 demo API — a read/aggregation view over the real Phase 3-7
 * pipeline, run against the five fixed named seed transactions. Every field
 * traces back to a real {@code RevenueRiskService}/{@code
 * RecoveryExecutionService} response or a persisted {@code AuditLog} row;
 * nothing here is fabricated.
 * <p>
 * Calling this endpoint does exercise the real pipeline — risk is
 * re-analyzed and {@code RecoveryExecutionService.execute()} is called
 * exactly as the Phase 7 endpoint would — so it is intentionally {@code
 * GET} for demo convenience rather than the usual REST idempotence
 * connotation. See {@code RecoveryDemoService} for why repeated calls stay
 * safe and repeatable without a separate reset mechanism.
 */
@RestController
@RequestMapping("/api/demo/recovery")
@RequiredArgsConstructor
public class RecoveryDemoController {

    private final RecoveryDemoService recoveryDemoService;

    @GetMapping
    public RecoveryDemoSummaryResponse runAll() {
        return recoveryDemoService.runAll();
    }

    @GetMapping("/{externalTransactionId}")
    public RecoveryDemoScenarioResponse runOne(@PathVariable String externalTransactionId) {
        return recoveryDemoService.runOne(externalTransactionId);
    }

    @ExceptionHandler(DemoScenarioNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(DemoScenarioNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
