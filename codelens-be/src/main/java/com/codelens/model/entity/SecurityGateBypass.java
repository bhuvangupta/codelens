package com.codelens.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/** Audit record of one security gate bypass. Insert-only. */
@Entity
@Table(name = "security_gate_bypasses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SecurityGateBypass {

    public enum Via { PR_COMMENT, CODELENS }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Repository.GitProvider provider;

    @Column(nullable = false)
    private String repoFullName;

    @Column(nullable = false)
    private Integer prNumber;

    @Column(nullable = false, length = 64)
    private String headSha;

    @Column(nullable = false)
    private String actorEmail;

    private String actorGithubLogin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Via via;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String reason;

    /** What the three checks said when the bypass was applied. */
    @Column(columnDefinition = "TEXT")
    private String findingsSnapshot;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
