package com.wahid.multi_format_large_file_processor.service;

import com.wahid.multi_format_large_file_processor.dto.ParseResult;

import java.io.InputStream;
import java.io.IOException;

/**
 * Interface for parsing customer data from different file formats.
 */
public interface FileParser {

    /**
     * Parses the given input stream based on the implementing strategy.
     *
     * @param inputStream The input stream containing the file data.
     * @param createdBy Identifier for the user/process creating the records.
     * @return A ParseResult containing lists of valid customers and processing processingErrors.
     * @throws IOException If an I/O error occurs during reading.
     */
    ParseResult parse(InputStream inputStream, String createdBy) throws IOException;

    /**
     * Indicates the file type supported by this parser (e.g., "csv", "excel").
     * Used for strategy selection.
     * @return The supported file type identifier.
     */
    String getSupportedFileType();
}