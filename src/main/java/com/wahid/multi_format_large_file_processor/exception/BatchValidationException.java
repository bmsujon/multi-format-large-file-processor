package com.wahid.multi_format_large_file_processor.exception;

// Custom exception for batch validation processingErrors to enable specific skipping
public class BatchValidationException extends RuntimeException {
    public BatchValidationException(String message) {
        super(message);
    }

    public BatchValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}