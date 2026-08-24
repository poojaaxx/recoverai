package com.recoverai.controller;

import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.dto.TransactionDetailResponse;
import com.recoverai.dto.TransactionSummaryResponse;
import com.recoverai.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Minimal read-only endpoints proving the persistence layer works over
 * HTTP. Full dashboard/detail endpoints (risk, recovery attempts, audit
 * trail) belong to later phases once those engines exist.
 * <p>
 * Methods are {@code @Transactional(readOnly = true)} so the {@code
 * customer} lazy association can be read while mapping to a DTO — with
 * {@code spring.jpa.open-in-view: false}, there is otherwise no open
 * Hibernate session by the time the response body is serialized. A
 * dedicated service layer (later phases) will own this instead of the
 * controller.
 */
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionController {

    private final TransactionRepository transactionRepository;

    @GetMapping
    public Page<TransactionSummaryResponse> list(
            @RequestParam(required = false) TransactionStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<Transaction> page = status != null
                ? transactionRepository.findByStatus(status, pageable)
                : transactionRepository.findAll(pageable);
        return page.map(TransactionSummaryResponse::from);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionDetailResponse> getById(@PathVariable UUID id) {
        return transactionRepository.findById(id)
                .map(TransactionDetailResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
