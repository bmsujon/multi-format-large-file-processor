package com.wahid.multi_format_large_file_processor.service;

import com.wahid.multi_format_large_file_processor.entity.Customer;
import java.util.List;

/**
 * Holds the result of a file parsing operation.
 *
 * @param validCustomers List of customers successfully parsed and validated.
 * @param processingErrors List of error messages encountered during parsing/validation.
 * @param totalRowsProcessed Total number of data rows attempted (excluding header).
 * @param failedRows Total number of rows that failed parsing or validation.
 */
public record ParseResult_old(
        List<Customer> validCustomers,
        List<String> processingErrors,
        int totalRowsProcessed,
        int failedRows
) {
}