package com.codelens.api.dto;

import java.util.List;

public record PagedReviewResponse(
    List<ReviewResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasNext,
    boolean hasPrevious
) {
    public static PagedReviewResponse from(org.springframework.data.domain.Page<com.codelens.model.entity.Review> page) {
        return new PagedReviewResponse(
            page.getContent().stream().map(ReviewResponse::from).toList(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages(),
            page.hasNext(),
            page.hasPrevious()
        );
    }
}
