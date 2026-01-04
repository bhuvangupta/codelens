package com.codelens.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CommitReviewRequest(
    @NotBlank(message = "Commit URL is required")
    String commitUrl,
    Boolean includeOptimization
) {
    public CommitReviewRequest(String commitUrl) {
        this(commitUrl, false);
    }
}
