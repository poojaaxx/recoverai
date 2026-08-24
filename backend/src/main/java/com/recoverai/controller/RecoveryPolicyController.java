package com.recoverai.controller;

import com.recoverai.dto.RecoveryPolicyDecisionResponse;
import com.recoverai.dto.RecoveryPolicyEvaluateRequest;
import com.recoverai.policy.RecoveryPolicyService;
import com.recoverai.risk.TransactionNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Recovery Safety / Policy Engine API (Phase 4). Evaluation only - this
 * endpoint never executes a recovery action, only decides whether one
 * would be allowed. See {@link RecoveryPolicyService}.
 */
@RestController
@RequestMapping("/api/recovery-policy")
@RequiredArgsConstructor
public class RecoveryPolicyController {

    private final RecoveryPolicyService recoveryPolicyService;

    @PostMapping("/evaluate/{transactionId}")
    public RecoveryPolicyDecisionResponse evaluate(@PathVariable UUID transactionId,
                                                     @Valid @RequestBody RecoveryPolicyEvaluateRequest request) {
        return recoveryPolicyService.evaluate(transactionId, request.action());
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(TransactionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    /** Covers both an unparseable/unknown-enum request body and a body missing the required "action" field. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid or missing recovery action."));
    }
}
