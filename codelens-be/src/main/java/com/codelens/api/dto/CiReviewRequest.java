package com.codelens.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for CI/CD triggered PR reviews
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CiReviewRequest {

    @NotBlank(message = "PR URL is required")
    @Pattern(
        regexp = "https://(github\\.com|gitlab\\.com)/[\\w.-]+/[\\w.-]+/(pull|merge_requests)/\\d+",
        message = "Invalid PR URL format. Expected: https://github.com/owner/repo/pull/123 or https://gitlab.com/owner/repo/merge_requests/123"
    )
    private String prUrl;

    /**
     * Optional: Override the default LLM provider for this review
     */
    private String llmProvider;

    /**
     * Optional: Callback URL to POST results when review completes
     */
    private String callbackUrl;

    /**
     * Optional: Custom metadata to include in the response (e.g., Jenkins build ID)
     */
    private String metadata;

    /**
     * Optional: Priority level for the review (normal, high)
     */
    @Builder.Default
    private String priority = "normal";
}
