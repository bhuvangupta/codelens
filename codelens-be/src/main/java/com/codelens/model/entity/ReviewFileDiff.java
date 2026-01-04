package com.codelens.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "review_file_diffs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewFileDiff {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "old_path", length = 500)
    private String oldPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FileStatus status;

    @Builder.Default
    private Integer additions = 0;

    @Builder.Default
    private Integer deletions = 0;

    @Column(columnDefinition = "LONGTEXT")
    private String patch;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum FileStatus {
        ADDED,
        MODIFIED,
        DELETED,
        RENAMED
    }
}
