package com.recoverai.execution;

import com.recoverai.config.RecoveryAgentProperties;
import com.recoverai.domain.WebhookProcessingStatus;
import com.recoverai.dto.ObservabilityMetricsResponse;
import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.WebhookEventRepository;
import com.recoverai.webhook.PaymentConfirmationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Production readiness phase: a pure read/aggregation layer - same
 * discipline as {@code RecoveryMetricsService} - assembling policy,
 * webhook, and provider observability counts. Has no dependency on {@code
 * RevenueRiskService}, {@code RecoveryPolicyService}, or {@code
 * PaymentGateway} beyond the read-only repositories/service it needs to
 * report what already happened; it never influences any decision.
 */
@Service
public class ObservabilityService {

    private static final String POLICY_EVALUATED_EVENT = "RECOVERY_POLICY_EVALUATED";

    private final AuditLogRepository auditLogRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final RecoveryAttemptRepository recoveryAttemptRepository;
    private final PaymentConfirmationService paymentConfirmationService;
    private final RecoveryAgentProperties recoveryAgentProperties;

    public ObservabilityService(AuditLogRepository auditLogRepository,
                                 WebhookEventRepository webhookEventRepository,
                                 RecoveryAttemptRepository recoveryAttemptRepository,
                                 PaymentConfirmationService paymentConfirmationService,
                                 RecoveryAgentProperties recoveryAgentProperties) {
        this.auditLogRepository = auditLogRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.recoveryAttemptRepository = recoveryAttemptRepository;
        this.paymentConfirmationService = paymentConfirmationService;
        this.recoveryAgentProperties = recoveryAgentProperties;
    }

    @Transactional(readOnly = true)
    public ObservabilityMetricsResponse getMetrics() {
        Map<String, Long> decisionCounts = auditLogRepository.countGroupedByDecision(POLICY_EVALUATED_EVENT).stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> row.getDecision() == null ? "" : row.getDecision(),
                        AuditLogRepository.DecisionCount::getTotal));

        var policyDecisions = new ObservabilityMetricsResponse.PolicyDecisionCounts(
                decisionCounts.getOrDefault("ALLOW", 0L),
                decisionCounts.getOrDefault("BLOCK", 0L),
                decisionCounts.getOrDefault("ESCALATE", 0L),
                decisionCounts.getOrDefault("STOP", 0L));

        long processed = webhookEventRepository.countByProcessingStatus(WebhookProcessingStatus.PROCESSED);
        long rejected = webhookEventRepository.countByProcessingStatus(WebhookProcessingStatus.REJECTED);
        long ignored = webhookEventRepository.countByProcessingStatus(WebhookProcessingStatus.IGNORED);
        long invalidSignature = paymentConfirmationService.invalidSignatureCount();
        long malformedPayload = paymentConfirmationService.malformedPayloadCount();

        var webhooks = new ObservabilityMetricsResponse.WebhookCounts(
                processed + rejected + ignored + invalidSignature + malformedPayload,
                processed, rejected, ignored, invalidSignature, malformedPayload);

        List<ObservabilityMetricsResponse.ProviderCounts> providers = recoveryAttemptRepository
                .countGroupedByProviderAndStatus().stream()
                .map(row -> new ObservabilityMetricsResponse.ProviderCounts(
                        row.getProvider(), row.getStatus().name(), row.getTotal()))
                .toList();

        return new ObservabilityMetricsResponse(policyDecisions, webhooks, providers,
                recoveryAgentProperties.getProvider());
    }
}
