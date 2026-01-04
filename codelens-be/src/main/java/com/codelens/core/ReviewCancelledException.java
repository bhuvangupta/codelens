package com.codelens.core;

/**
 * Exception thrown when a review is cancelled.
 */
public class ReviewCancelledException extends RuntimeException {

    public ReviewCancelledException(String message) {
        super(message);
    }

    public ReviewCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
