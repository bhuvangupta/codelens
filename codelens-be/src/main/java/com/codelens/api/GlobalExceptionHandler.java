package com.codelens.api;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handle validation errors from @Valid on request bodies
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now().toString());
        response.put("status", HttpStatus.BAD_REQUEST.value());
        response.put("error", "Validation Failed");

        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(
                error -> error.getField(),
                error -> error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value",
                (existing, replacement) -> existing
            ));
        response.put("fieldErrors", fieldErrors);

        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Handle constraint violations from @Validated on path/query parameters
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now().toString());
        response.put("status", HttpStatus.BAD_REQUEST.value());
        response.put("error", "Validation Failed");

        Map<String, String> violations = ex.getConstraintViolations().stream()
            .collect(Collectors.toMap(
                v -> v.getPropertyPath().toString(),
                v -> v.getMessage(),
                (existing, replacement) -> existing
            ));
        response.put("violations", violations);

        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Handle type mismatch errors (e.g., passing string for int parameter)
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now().toString());
        response.put("status", HttpStatus.BAD_REQUEST.value());
        response.put("error", "Invalid Parameter Type");
        response.put("message", String.format("Parameter '%s' should be of type %s",
            ex.getName(),
            ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown"));

        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Handle database access errors
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccessException(DataAccessException ex) {
        log.error("Database error occurred", ex);

        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now().toString());
        response.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.put("error", "Database Error");
        response.put("message", "A database error occurred. Please try again later.");

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * Handle RuntimeException with meaningful messages
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex) {
        log.error("Runtime exception: {}", ex.getMessage(), ex);

        String userMessage = extractUserFriendlyMessage(ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "status", 500,
                "error", "Internal Server Error",
                "message", userMessage,
                "details", ex.getMessage() != null ? ex.getMessage() : "Unknown error"
            ));
    }

    /**
     * Handle IO exceptions (network timeouts, etc.)
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> handleIOException(IOException ex) {
        log.error("IO exception: {}", ex.getMessage(), ex);

        String userMessage = "Network error occurred";
        if (ex.getMessage() != null && ex.getMessage().contains("timed out")) {
            userMessage = "Connection to GitHub timed out. Please check your network and try again.";
        }

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "status", 503,
                "error", "Service Unavailable",
                "message", userMessage,
                "details", ex.getMessage() != null ? ex.getMessage() : "Network error"
            ));
    }

    /**
     * Handle IllegalArgumentException
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("Invalid argument: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "status", 400,
                "error", "Bad Request",
                "message", ex.getMessage() != null ? ex.getMessage() : "Invalid request"
            ));
    }

    /**
     * Extract a user-friendly message from exception
     */
    private String extractUserFriendlyMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null) {
            return "An unexpected error occurred";
        }

        // GitHub API errors
        if (message.contains("Failed to get pull request")) {
            if (ex.getCause() != null && ex.getCause().getMessage() != null) {
                String cause = ex.getCause().getMessage();
                if (cause.contains("timed out")) {
                    return "Connection to GitHub timed out. Please check your network connection and try again.";
                }
                if (cause.contains("404") || cause.contains("Not Found")) {
                    return "Pull request not found. Please check the PR URL is correct and you have access to the repository.";
                }
                if (cause.contains("401") || cause.contains("Unauthorized")) {
                    return "GitHub authentication failed. Please check your GitHub credentials.";
                }
                if (cause.contains("403") || cause.contains("Forbidden")) {
                    return "Access denied. Please check your GitHub permissions for this repository.";
                }
            }
            return "Failed to access GitHub. Please check your network and try again.";
        }

        // LLM errors
        if (message.contains("LLM") || message.contains("provider")) {
            return "AI service error. Please try again or check LLM provider configuration.";
        }

        // Generic timeout
        if (message.contains("timed out") || message.contains("timeout")) {
            return "Request timed out. Please try again.";
        }

        // Return sanitized message
        return message.length() > 200 ? message.substring(0, 200) + "..." : message;
    }
}
