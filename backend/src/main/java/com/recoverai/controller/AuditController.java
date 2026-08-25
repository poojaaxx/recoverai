package com.recoverai.controller;

import com.recoverai.dto.AuditTimelineEntryResponse;
import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.risk.TransactionNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only audit trail access for any transaction (not just the 5
 * curated demo scenarios, which already expose their own timeline bundled
 * inside {@code GET /api/demo/recovery}). Added for the interactive
 * frontend console so the audit panel can be refreshed independently,
 * without re-running the demo endpoint's full evaluate/execute pipeline
 * as a side effect of what should be a pure read. No new write path, no
 * new decision logic - identical projection to the one already used by
 * {@code RecoveryDemoService} ({@link AuditTimelineEntryResponse}).
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogRepository auditLogRepository;
    private final TransactionRepository transactionRepository;

    @GetMapping("/{transactionId}")
    @Transactional(readOnly = true)
    public List<AuditTimelineEntryResponse> getByTransaction(@PathVariable UUID transactionId) {
        if (!transactionRepository.existsById(transactionId)) {
            throw new TransactionNotFoundException(transactionId);
        }
        return auditLogRepository.findByTransactionIdOrderByTimestampAsc(transactionId).stream()
                .map(AuditTimelineEntryResponse::from)
                .toList();
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(TransactionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
