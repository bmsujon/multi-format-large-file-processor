package com.wahid.multi_format_large_file_processor.dto;

import java.util.List;

/**
 * Holds the summary results of processing a file.
 *
 * @param totalProcessedByParser Count of data rows attempted by the parser (excluding header).
 * @param totalSaved             Count of records successfully saved to the database.
 * @param totalFailed            Total count of records that failed during parsing or validation/saving.
 * @param processingErrors       List of error messages accumulated during processing.
 * @param processingTimeMillis   Total time taken for processing in milliseconds.
 */
public record ProcessingSummary(
        int totalProcessedByParser,
        int totalSaved,
        int totalFailed,
        List<String> processingErrors,
        long processingTimeMillis
) {
}