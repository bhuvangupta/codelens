package com.codelens.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ReviewRequest(
    @NotBlank(message = "PR URL is required")
    String prUrl,
    Boolean includeOptimization,
    String ticketContent,
    String ticketId
) {
    public ReviewRequest(String prUrl) {
        this(prUrl, false, null, null);
    }

    public ReviewRequest(String prUrl, Boolean includeOptimization) {
        this(prUrl, includeOptimization, null, null);
    }
}
