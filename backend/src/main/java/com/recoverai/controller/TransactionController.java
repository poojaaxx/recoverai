package com.recoverai.controller;

import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.RiskLevel;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.dto.TransactionDetailResponse;
import com.recoverai.dto.TransactionFullDetailResponse;
import com.recoverai.dto.TransactionListItemResponse;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.risk.TransactionNotFoundException;
import com.recoverai.transaction.TransactionDashboardService;
import com.recoverai.transaction.TransactionSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Transaction read endpoints. {@code /} lists/searches/filters/sorts
 * across every transaction in the database (Phase 13's general-purpose
 * dashboard, see {@link TransactionDashboardService}) - it does not
 * single out the 5 curated demo scenarios. {@code /{id}} stays the
 * minimal single-transaction projection used by the interactive console's
 * "Refresh transaction" action (unchanged, Phase 11); {@code /{id}/detail}
 * is the richer bundle (risk, recovery history, audit) the new dashboard's
 * detail page uses.
 */
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionRepository transactionRepository;
    private final TransactionDashboardService transactionDashboardService;

    @GetMapping
    public Page<TransactionListItemResponse> list(
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) RiskLevel riskLevel,
            @RequestParam(required = false) String failureCategory,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "false") boolean atRiskOnly,
            @RequestParam(required = false, defaultValue = "false") boolean recoveredOnly,
            @RequestParam(required = false) RecoveryAttemptStatus recoveryAttemptStatus,
            @RequestParam(required = false, defaultValue = "NEWEST") TransactionSort sort,
            @PageableDefault(size = 20) Pageable pageable) {
        return transactionDashboardService.search(status, riskLevel, failureCategory, paymentMethod,
                minAmount, maxAmount, search, atRiskOnly, recoveredOnly, recoveryAttemptStatus, sort, pageable);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<TransactionDetailResponse> getById(@PathVariable UUID id) {
        return transactionRepository.findById(id)
                .map(TransactionDetailResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/detail")
    public TransactionFullDetailResponse getFullDetail(@PathVariable UUID id) {
        return transactionDashboardService.getFullDetail(id);
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(TransactionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
