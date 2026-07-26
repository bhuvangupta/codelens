package com.codelens.exception;

/**
 * Raised when a review is submitted by a user who belongs to no organization
 * and could not be auto-enrolled into the repository's organization.
 *
 * <p>Thrown after the auto-association attempt in ReviewService, so a user
 * whose email domain matches the repository's organization is enrolled rather
 * than rejected. Raised before the review is persisted and before the async
 * LLM job is triggered, so no budget is spent.
 */
public class NoOrganizationException extends RuntimeException {

    public NoOrganizationException(String message) {
        super(message);
    }
}
