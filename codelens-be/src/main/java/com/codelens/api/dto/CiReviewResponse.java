package com.codelens.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Response DTO for CI/CD triggered PR reviews
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CiReviewResponse {

    private UUID reviewId;
    private String status;
    private String message;
    private String prUrl;

    // Review results (populated when status is COMPLETED)
    private String summary;
    private Integer issuesFound;
    private Integer criticalIssues;
    private Integer highIssues;
    private Integer mediumIssues;
    private Integer lowIssues;

    // For callback tracking
    private String metadata;
}
