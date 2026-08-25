package com.recoverai.repository;

import com.recoverai.domain.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    Optional<WebhookEvent> findByProviderAndProviderEventId(String provider, String providerEventId);
}
