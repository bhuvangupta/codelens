package com.codelens.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CommitReviewRequest(
    @NotBlank(message = "Commit URL is required")
    String commitUrl,
    Boolean includeOptimization,
    String ticketContent,
    String ticketId
) {
    public CommitReviewRequest(String commitUrl) {
        this(commitUrl, false, null, null);
    }

    public CommitReviewRequest(String commitUrl, Boolean includeOptimization) {
        this(commitUrl, includeOptimization, null, null);
    }
}
