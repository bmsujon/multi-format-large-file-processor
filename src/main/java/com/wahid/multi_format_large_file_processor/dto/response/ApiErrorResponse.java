package com.wahid.multi_format_large_file_processor.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Standardized API Error Response structure.
 * Using JsonInclude(JsonInclude.Include.NON_NULL) to omit null fields (like details) from the JSON output.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error, // e.g., HttpStatus reason phrase like "Bad Request"
        String message, // More specific error message
        String path, // The request path where the error occurred
        List<String> details // Optional list for specific processingErrors, e.g., validation failures
) {
    // Constructor for processingErrors without details
    public ApiErrorResponse(HttpStatus status, String message, String path) {
        this(LocalDateTime.now(), status.value(), status.getReasonPhrase(), message, path, null);
    }

    // Constructor for processingErrors with details (like validation processingErrors)
    public ApiErrorResponse(HttpStatus status, String message, String path, List<String> details) {
        this(LocalDateTime.now(), status.value(), status.getReasonPhrase(), message, path, details);
    }
}