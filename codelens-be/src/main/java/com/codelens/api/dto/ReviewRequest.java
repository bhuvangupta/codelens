package com.codelens.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ReviewRequest(
    @NotBlank(message = "PR URL is required")
    String prUrl,
    Boolean includeOptimization
) {
    public ReviewRequest(String prUrl) {
        this(prUrl, false);
    }
}
