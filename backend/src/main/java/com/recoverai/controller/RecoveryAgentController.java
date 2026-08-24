package com.recoverai.controller;

import com.recoverai.agent.RecoveryAgentService;
import com.recoverai.dto.RecoveryAgentBatchResponse;
import com.recoverai.dto.RecoveryAgentEvaluationResponse;
import com.recoverai.risk.TransactionNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * AI Recovery Agent API (Phase 5). "Ask the AI agent what it recommends"
 * - the server builds the context and asks the AI itself; no action needs
 * to (or should) be supplied by the caller. Every response's {@code
 * finalAction} is authorized by {@code com.recoverai.policy.
 * RecoveryPolicyService}, not by the AI - see {@link RecoveryAgentService}.
 * This endpoint never executes a recovery action, never calls Razorpay,
 * and never claims money was recovered.
 */
@RestController
@RequestMapping("/api/recovery-agent")
@RequiredArgsConstructor
public class RecoveryAgentController {

    private final RecoveryAgentService recoveryAgentService;

    @PostMapping("/evaluate/{transactionId}")
    public RecoveryAgentEvaluationResponse evaluate(@PathVariable UUID transactionId) {
        return recoveryAgentService.evaluate(transactionId);
    }

    @PostMapping("/evaluate-all")
    public RecoveryAgentBatchResponse evaluateAll() {
        return recoveryAgentService.evaluateAll();
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(TransactionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
