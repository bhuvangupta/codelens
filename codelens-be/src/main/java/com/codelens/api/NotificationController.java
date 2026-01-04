package com.codelens.api;

import com.codelens.model.entity.Notification;
import com.codelens.model.entity.NotificationPreference;
import com.codelens.model.entity.User;
import com.codelens.repository.UserRepository;
import com.codelens.security.AuthenticatedUser;
import com.codelens.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    /**
     * Get notifications for the current user
     */
    @GetMapping
    public ResponseEntity<List<NotificationDto>> getNotifications(
            @AuthenticationPrincipal AuthenticatedUser auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        User user = getUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Page<Notification> notifications = notificationService.getNotificationsForUser(user.getId(), page, size);
        return ResponseEntity.ok(notifications.getContent().stream().map(NotificationDto::from).toList());
    }

    /**
     * Get unread notification count
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(@AuthenticationPrincipal AuthenticatedUser auth) {
        User user = getUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        long count = notificationService.getUnreadCount(user.getId());
        return ResponseEntity.ok(Map.of("count", count));
    }

    /**
     * Mark a notification as read
     */
    @PostMapping("/{id}/read")
    public ResponseEntity<?> markAsRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser auth) {

        User user = getUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Verify notification belongs to the authenticated user
        Notification notification = notificationService.getNotification(id).orElse(null);
        if (notification == null) {
            return ResponseEntity.notFound().build();
        }

        if (notification.getUser() == null || !notification.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You can only mark your own notifications as read"));
        }

        notificationService.markAsRead(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Mark all notifications as read
     */
    @PostMapping("/read-all")
    public ResponseEntity<Map<String, Integer>> markAllAsRead(@AuthenticationPrincipal AuthenticatedUser auth) {
        User user = getUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        int count = notificationService.markAllAsRead(user.getId());
        return ResponseEntity.ok(Map.of("count", count));
    }

    /**
     * Get notification preferences
     */
    @GetMapping("/preferences")
    public ResponseEntity<PreferenceDto> getPreferences(@AuthenticationPrincipal AuthenticatedUser auth) {
        User user = getUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        NotificationPreference prefs = notificationService.getPreferencesForUser(user.getId());
        return ResponseEntity.ok(PreferenceDto.from(prefs));
    }

    /**
     * Update notification preferences
     */
    @PutMapping("/preferences")
    public ResponseEntity<PreferenceDto> updatePreferences(
            @RequestBody NotificationService.UpdatePreferencesRequest request,
            @AuthenticationPrincipal AuthenticatedUser auth) {

        User user = getUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        NotificationPreference prefs = notificationService.updatePreferences(user.getId(), request);
        return ResponseEntity.ok(PreferenceDto.from(prefs));
    }

    private User getUser(AuthenticatedUser auth) {
        if (auth == null) return null;
        return userRepository.findByEmail(auth.email()).orElse(null);
    }

    // Response DTOs
    public record NotificationDto(
            String id,
            String type,
            String title,
            String message,
            String referenceType,
            String referenceId,
            boolean isRead,
            String createdAt
    ) {
        public static NotificationDto from(Notification notification) {
            return new NotificationDto(
                    notification.getId().toString(),
                    notification.getType().name(),
                    notification.getTitle(),
                    notification.getMessage(),
                    notification.getReferenceType(),
                    notification.getReferenceId() != null ? notification.getReferenceId().toString() : null,
                    Boolean.TRUE.equals(notification.getIsRead()),
                    notification.getCreatedAt() != null ? notification.getCreatedAt().toString() : null
            );
        }
    }

    public record PreferenceDto(
            boolean emailEnabled,
            boolean inAppEnabled,
            boolean reviewCompleted,
            boolean reviewFailed,
            boolean criticalIssues
    ) {
        public static PreferenceDto from(NotificationPreference prefs) {
            return new PreferenceDto(
                    Boolean.TRUE.equals(prefs.getEmailEnabled()),
                    Boolean.TRUE.equals(prefs.getInAppEnabled()),
                    Boolean.TRUE.equals(prefs.getReviewCompleted()),
                    Boolean.TRUE.equals(prefs.getReviewFailed()),
                    Boolean.TRUE.equals(prefs.getCriticalIssues())
            );
        }
    }
}
