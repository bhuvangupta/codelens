package com.codelens.api;

import com.codelens.model.entity.User;
import com.codelens.model.entity.WebhookConfig;
import com.codelens.repository.UserRepository;
import com.codelens.security.AuthenticatedUser;
import com.codelens.service.WebhookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class WebhookConfigController {

    private final WebhookService webhookService;
    private final UserRepository userRepository;

    /**
     * Get all webhooks for the user's organization
     */
    @GetMapping
    public ResponseEntity<List<WebhookDto>> getWebhooks(@AuthenticationPrincipal AuthenticatedUser auth) {
        User user = getUser(auth);
        if (user == null || user.getOrganization() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<WebhookConfig> webhooks = webhookService.getWebhooksForOrganization(user.getOrganization().getId());
        return ResponseEntity.ok(webhooks.stream().map(WebhookDto::from).toList());
    }

    /**
     * Create a new webhook
     */
    @PostMapping
    public ResponseEntity<?> createWebhook(
            @Valid @RequestBody WebhookService.CreateWebhookRequest request,
            @AuthenticationPrincipal AuthenticatedUser auth) {

        User user = getUser(auth);
        if (user == null || user.getOrganization() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Only admins can create webhooks
        if (user.getRole() != User.UserRole.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only organization admins can manage webhooks"));
        }

        try {
            WebhookConfig webhook = webhookService.createWebhook(request, user.getOrganization());
            return ResponseEntity.status(HttpStatus.CREATED).body(WebhookDto.from(webhook));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update a webhook
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateWebhook(
            @PathVariable UUID id,
            @Valid @RequestBody WebhookService.UpdateWebhookRequest request,
            @AuthenticationPrincipal AuthenticatedUser auth) {

        User user = getUser(auth);
        if (user == null || user.getOrganization() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Only admins can update webhooks
        if (user.getRole() != User.UserRole.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only organization admins can manage webhooks"));
        }

        // Verify webhook belongs to user's organization
        WebhookConfig existingWebhook = webhookService.getWebhook(id).orElse(null);
        if (existingWebhook == null) {
            return ResponseEntity.notFound().build();
        }

        if (existingWebhook.getOrganization() == null ||
            !existingWebhook.getOrganization().getId().equals(user.getOrganization().getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You can only modify webhooks in your organization"));
        }

        try {
            WebhookConfig webhook = webhookService.updateWebhook(id, request);
            return ResponseEntity.ok(WebhookDto.from(webhook));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Delete a webhook
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteWebhook(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser auth) {

        User user = getUser(auth);
        if (user == null || user.getOrganization() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Only admins can delete webhooks
        if (user.getRole() != User.UserRole.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only organization admins can manage webhooks"));
        }

        // Verify webhook belongs to user's organization
        WebhookConfig existingWebhook = webhookService.getWebhook(id).orElse(null);
        if (existingWebhook == null) {
            return ResponseEntity.notFound().build();
        }

        if (existingWebhook.getOrganization() == null ||
            !existingWebhook.getOrganization().getId().equals(user.getOrganization().getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You can only delete webhooks in your organization"));
        }

        try {
            webhookService.deleteWebhook(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get available webhook event types
     */
    @GetMapping("/events")
    public ResponseEntity<List<EventTypeDto>> getEventTypes() {
        List<EventTypeDto> events = List.of(
                new EventTypeDto(WebhookConfig.EVENT_REVIEW_COMPLETED, "Review Completed", "Triggered when a code review is completed"),
                new EventTypeDto(WebhookConfig.EVENT_REVIEW_FAILED, "Review Failed", "Triggered when a code review fails"),
                new EventTypeDto(WebhookConfig.EVENT_CRITICAL_ISSUES, "Critical Issues Found", "Triggered when critical security issues are found")
        );
        return ResponseEntity.ok(events);
    }

    private User getUser(AuthenticatedUser auth) {
        if (auth == null) return null;
        return userRepository.findByEmail(auth.email()).orElse(null);
    }

    // Response DTOs
    public record WebhookDto(
            String id,
            String name,
            String url,
            boolean hasSecret,
            List<String> events,
            boolean enabled,
            int failureCount,
            int retryCount,
            String retryAt,
            String disabledAt,
            String lastDeliveryAt,
            String createdAt
    ) {
        public static WebhookDto from(WebhookConfig webhook) {
            return new WebhookDto(
                    webhook.getId().toString(),
                    webhook.getName(),
                    webhook.getUrl(),
                    webhook.getSecret() != null && !webhook.getSecret().isEmpty(),
                    webhook.getEvents(),
                    Boolean.TRUE.equals(webhook.getEnabled()),
                    webhook.getFailureCount() != null ? webhook.getFailureCount() : 0,
                    webhook.getRetryCount() != null ? webhook.getRetryCount() : 0,
                    webhook.getRetryAt() != null ? webhook.getRetryAt().toString() : null,
                    webhook.getDisabledAt() != null ? webhook.getDisabledAt().toString() : null,
                    webhook.getLastDeliveryAt() != null ? webhook.getLastDeliveryAt().toString() : null,
                    webhook.getCreatedAt() != null ? webhook.getCreatedAt().toString() : null
            );
        }
    }

    public record EventTypeDto(
            String id,
            String name,
            String description
    ) {}
}
