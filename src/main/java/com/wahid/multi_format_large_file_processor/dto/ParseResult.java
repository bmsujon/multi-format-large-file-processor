package com.wahid.multi_format_large_file_processor.dto;

import java.util.List;

public record ParseResult(
    List<CustomerRecord> validRecords, // Specific type
    List<String> processingErrors,
    int processedRowCount,
    int failedRowCount
) {}