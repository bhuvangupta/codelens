package com.codelens.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "notification_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Builder.Default
    private Boolean emailEnabled = true;

    @Builder.Default
    private Boolean inAppEnabled = true;

    @Builder.Default
    private Boolean reviewCompleted = true;

    @Builder.Default
    private Boolean reviewFailed = true;

    @Builder.Default
    private Boolean criticalIssues = true;
}
