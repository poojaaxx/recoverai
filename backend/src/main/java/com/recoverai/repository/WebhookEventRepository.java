package com.recoverai.repository;

import com.recoverai.domain.WebhookEvent;
import com.recoverai.domain.WebhookProcessingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    Optional<WebhookEvent> findByProviderAndProviderEventId(String provider, String providerEventId);

    /** Webhook observability ({@code ObservabilityService}) - every signature-verified delivery is persisted here, so this is a real count of what happened after the signature gate, not an estimate. */
    long countByProcessingStatus(WebhookProcessingStatus status);
}
