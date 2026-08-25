package com.recoverai.controller;

import com.recoverai.dto.RecoveryExecutionResponse;
import com.recoverai.dto.RecoveryMetricsResponse;
import com.recoverai.execution.RecoveryExecutionService;
import com.recoverai.execution.RecoveryMetricsService;
import com.recoverai.risk.TransactionNotFoundException;
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
 * Recovery Execution Pipeline API (Phase 7) - the only production-shaped
 * execution endpoint. No request body: the server derives the AI
 * recommendation and policy decision itself and executes only if
 * authorized - a client cannot supply or override the action, amount,
 * currency, or policy decision. See {@link RecoveryExecutionService}.
 * <p>
 * Deliberately does not exist: a raw {@code POST /api/payments/execute}
 * that would let a caller choose an arbitrary action and bypass AI/policy
 * (see Phase 6/7 spec).
 */
@RestController
@RequestMapping("/api/recovery")
@RequiredArgsConstructor
public class RecoveryExecutionController {

    private final RecoveryExecutionService recoveryExecutionService;
    private final RecoveryMetricsService recoveryMetricsService;

    @PostMapping("/{transactionId}/execute")
    public RecoveryExecutionResponse execute(@PathVariable UUID transactionId) {
        return recoveryExecutionService.execute(transactionId);
    }

    @GetMapping("/metrics")
    public RecoveryMetricsResponse metrics() {
        return recoveryMetricsService.getMetrics();
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(TransactionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
