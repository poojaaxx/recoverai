package com.recoverai.controller;

import com.recoverai.demo.DemoConfirmationService;
import com.recoverai.demo.DemoResetNotAvailableException;
import com.recoverai.demo.DemoResetService;
import com.recoverai.demo.DemoScenarioNotFoundException;
import com.recoverai.demo.RecoveryDemoService;
import com.recoverai.demo.TestConfirmationNotAvailableException;
import com.recoverai.dto.RecoveryDemoScenarioResponse;
import com.recoverai.dto.RecoveryDemoSummaryResponse;
import com.recoverai.dto.TestPaymentConfirmationResponse;
import com.recoverai.risk.TransactionNotFoundException;
import com.recoverai.seed.SeedReport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

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
 * connotation. See {@code RecoveryDemoService} for why the 5 named
 * scenarios stay safe and repeatable on their own; {@code POST
 * /api/demo/recovery/reset} (see {@link DemoResetService}) exists
 * separately for restoring the whole demo dataset — including anything
 * beyond the 5 scenarios, e.g. portfolio-wide batch executions — to a clean
 * slate.
 */
@RestController
@RequestMapping("/api/demo/recovery")
@RequiredArgsConstructor
public class RecoveryDemoController {

    private final RecoveryDemoService recoveryDemoService;
    private final DemoConfirmationService demoConfirmationService;
    private final DemoResetService demoResetService;

    @GetMapping
    public RecoveryDemoSummaryResponse runAll() {
        return recoveryDemoService.runAll();
    }

    @GetMapping("/{externalTransactionId}")
    public RecoveryDemoScenarioResponse runOne(@PathVariable String externalTransactionId) {
        return recoveryDemoService.runOne(externalTransactionId);
    }

    /**
     * P0.4 - drives a real, signed, self-issued webhook through the actual
     * {@code PaymentConfirmationService} confirmation path for a
     * transaction that already has a successful mock-provider execution.
     * See {@link DemoConfirmationService} for every safety gate on this
     * endpoint. Always returns a response explicitly labeled TEST/SIMULATION
     * - never presented as a real payment.
     */
    @PostMapping("/confirm-test-payment/{transactionId}")
    public TestPaymentConfirmationResponse confirmTestPayment(@PathVariable UUID transactionId) {
        return demoConfirmationService.confirmTestPayment(transactionId);
    }

    /**
     * Development/demo-only - restores the seeded demo dataset (transactions,
     * customers, revenue-risk rows, recovery attempts, webhook events, and
     * audit records) to its original deterministic state. See {@link
     * DemoResetService} for the exact safety gates. Never available unless
     * {@code DEMO_SEED_ENABLED=true}, which is off by default in every
     * profile including production.
     */
    @PostMapping("/reset")
    public SeedReport reset() {
        return demoResetService.reset();
    }

    @ExceptionHandler(DemoScenarioNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(DemoScenarioNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(TestConfirmationNotAvailableException.class)
    public ResponseEntity<Map<String, String>> handleNotAvailable(TestConfirmationNotAvailableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DemoResetNotAvailableException.class)
    public ResponseEntity<Map<String, String>> handleResetNotAvailable(DemoResetNotAvailableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleTransactionNotFound(TransactionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
