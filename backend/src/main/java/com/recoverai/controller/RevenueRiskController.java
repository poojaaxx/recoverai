package com.recoverai.controller;

import com.recoverai.domain.RiskLevel;
import com.recoverai.dto.BatchRiskAnalysisResponse;
import com.recoverai.dto.RevenueRiskMetricsResponse;
import com.recoverai.dto.RevenueRiskResponse;
import com.recoverai.repository.RevenueRiskRepository;
import com.recoverai.risk.RevenueRiskService;
import com.recoverai.risk.TransactionNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Revenue Risk Engine API (Phase 3). Deterministic risk analysis only —
 * no AI, no recovery execution, no safety-policy enforcement. See {@link
 * RevenueRiskService}.
 */
@RestController
@RequestMapping("/api/revenue-risk")
@RequiredArgsConstructor
public class RevenueRiskController {

    private final RevenueRiskService revenueRiskService;
    private final RevenueRiskRepository revenueRiskRepository;

    @GetMapping
    @Transactional(readOnly = true)
    public Page<RevenueRiskResponse> list(
            @RequestParam(required = false) RiskLevel riskLevel,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<com.recoverai.domain.RevenueRisk> page = riskLevel != null
                ? revenueRiskRepository.findByRiskLevel(riskLevel, pageable)
                : revenueRiskRepository.findAll(pageable);
        return page.map(RevenueRiskResponse::from);
    }

    @GetMapping("/metrics")
    public RevenueRiskMetricsResponse metrics() {
        return revenueRiskService.getMetrics();
    }

    @GetMapping("/{transactionId}")
    @Transactional(readOnly = true)
    public ResponseEntity<RevenueRiskResponse> getByTransaction(@PathVariable UUID transactionId) {
        return revenueRiskRepository.findByTransactionId(transactionId)
                .map(RevenueRiskResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/analyze/{transactionId}")
    public RevenueRiskResponse analyze(@PathVariable UUID transactionId) {
        return revenueRiskService.analyzeTransaction(transactionId);
    }

    @PostMapping("/analyze-all")
    public BatchRiskAnalysisResponse analyzeAll() {
        return revenueRiskService.analyzeAllAtRisk();
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(TransactionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
