package com.wahid.multi_format_large_file_processor.service;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
// Import the DTO
import com.wahid.multi_format_large_file_processor.dto.CustomerRecord;
import com.wahid.multi_format_large_file_processor.dto.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component("csv") // Bean name matches the file type identifier
public class CsvFileParser implements FileParser {

    private static final Logger log = LoggerFactory.getLogger(CsvFileParser.class);
    // Consider making these configurable via @Value
    private static final int PARALLEL_BATCH_SIZE = 200; // How many rows to process in parallel threads
    private static final long FUTURE_GET_TIMEOUT_SECONDS = 60; // Timeout for waiting on a single row task

    // --- Column Index Constants (Ensure these match your CSV structure) ---
    private static final int COL_CUSTOMER_ID = 1; // Adjust indices if CSV has no 'Index' column
    private static final int COL_FIRST_NAME = 2;
    private static final int COL_LAST_NAME = 3;
    private static final int COL_COMPANY = 4;
    private static final int COL_CITY = 5;
    private static final int COL_COUNTRY = 6;
    private static final int COL_PHONE_1 = 7;
    private static final int COL_PHONE_2 = 8;
    private static final int COL_EMAIL = 9;
    private static final int COL_SUBSCRIPTION_DATE = 10;
    private static final int COL_WEBSITE = 11;
    // Minimum columns needed based on the highest index used + 1
    private static final int MIN_EXPECTED_COLUMNS = COL_WEBSITE + 1; // Should be 12 if indices start from 0/1 as above

    // --- Date Formatters ---
    private static final DateTimeFormatter PRIMARY_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE; // yyyy-MM-dd
    private static final DateTimeFormatter FALLBACK_DATE_FORMATTER_MDY = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private final ExecutorService virtualThreadExecutor;

    // Use constructor injection
    @Autowired
    public CsvFileParser(@Qualifier("virtualThreadExecutor") ExecutorService virtualThreadExecutor) {
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    @Override
    public String getSupportedFileType() {
        return "csv";
    }

    @Override
    public ParseResult parse(InputStream inputStream, String createdBy) throws IOException {
        Queue<CustomerRecord> validRecordsQueue = new ConcurrentLinkedQueue<>();
        Queue<String> processingErrorsQueue = new ConcurrentLinkedQueue<>();
        AtomicInteger processedRowCount = new AtomicInteger(0); // Counts data rows attempted (after header)
        AtomicInteger failedRowCount = new AtomicInteger(0);
        int headerLineNumber = 1; // Assuming header is the first line

        log.info("Starting CSV parsing.");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
             CSVReader csvReader = new CSVReaderBuilder(reader)
                     .withSkipLines(headerLineNumber) // Skip header row
                     .build()) {

            List<Future<?>> futures = Collections.synchronizedList(new ArrayList<>(PARALLEL_BATCH_SIZE));
            String[] line;
            int dataLineNumber = headerLineNumber; // Start counting data lines from here

            while ((line = csvReader.readNext()) != null) {
                final int currentLineNumber = ++dataLineNumber; // Actual line number in the file (1-based)
                final String[] currentLine = Arrays.copyOf(line, line.length); // Defensive copy
                processedRowCount.incrementAndGet(); // Increment for each data row attempted

                futures.add(virtualThreadExecutor.submit(() -> {
                    try {
                        // Call the parsing method
                        Optional<CustomerRecord> recordOpt = parseRowToRecord(currentLine, currentLineNumber, createdBy, processingErrorsQueue, failedRowCount);
                        recordOpt.ifPresent(validRecordsQueue::add);
                    } catch (Exception taskEx) {
                        log.error("Unexpected error in CSV row processing task for line {}: {}", currentLineNumber, taskEx.getMessage(), taskEx);
                        processingErrorsQueue.add("Line " + currentLineNumber + ": Unexpected internal error during processing: " + taskEx.getMessage());
                        failedRowCount.incrementAndGet();
                    }
                }));

                // Wait for futures if batch size is reached
                if (futures.size() >= PARALLEL_BATCH_SIZE) {
                    log.debug("Reached CSV batch size ({}), waiting for tasks...", PARALLEL_BATCH_SIZE);
                    waitForFutures(futures, processingErrorsQueue);
                }
            }
            // Wait for any remaining futures after the loop
            log.debug("Waiting for final batch of CSV row processing tasks to complete.");
            waitForFutures(futures, processingErrorsQueue);

        } catch (CsvValidationException e) {
            log.error("CSV structure validation error during processing near line {}", e.getLineNumber(), e);
            processingErrorsQueue.add("CSV structure validation failed near line " + e.getLineNumber() + ": " + e.getMessage());
        } catch (IOException ioEx) {
            log.error("I/O error during CSV processing", ioEx);
            processingErrorsQueue.add("I/O error reading CSV file: " + ioEx.getMessage());
            validRecordsQueue.clear();
            failedRowCount.set(processedRowCount.get() + failedRowCount.get());
            processedRowCount.set(0);
            throw ioEx;
        } catch (Exception e) {
            log.error("Unexpected error during CSV processing", e);
            processingErrorsQueue.add("Unexpected error during CSV processing: " + e.getMessage());
            validRecordsQueue.clear();
            failedRowCount.set(processedRowCount.get() + failedRowCount.get());
            processedRowCount.set(0);
            throw new IOException("Failed to process CSV file: " + e.getMessage(), e);
        }

        log.info("CSV parsing finished. Processed data rows: {}, Failed rows: {}, Valid Records Found: {}",
                processedRowCount.get(), failedRowCount.get(), validRecordsQueue.size());

        return new ParseResult(
                new ArrayList<>(validRecordsQueue),
                new ArrayList<>(processingErrorsQueue),
                processedRowCount.get(),
                failedRowCount.get()
        );
    }

    /**
     * Waits for the submitted row processing tasks to complete and handles processingErrors.
     * Clears the futures list after processing.
     */
    private void waitForFutures(List<Future<?>> futures, Queue<String> processingErrorsQueue) {
        if (futures.isEmpty()) {
            return;
        }
        List<Future<?>> futuresToProcess = new ArrayList<>(futures);
        futures.clear(); // Clear the original list

        for (Future<?> future : futuresToProcess) {
            try {
                future.get(FUTURE_GET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("CSV row processing task interrupted.", ie);
                processingErrorsQueue.add("Processing was interrupted.");
            } catch (ExecutionException ee) {
                log.error("Error reported executing CSV row processing task.", ee.getCause());
                processingErrorsQueue.add("Internal error during row processing: " + (ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage()));
            } catch (TimeoutException te) {
                log.error("Timeout waiting for CSV row processing task to complete after {} seconds.", FUTURE_GET_TIMEOUT_SECONDS, te);
                processingErrorsQueue.add("Timeout processing a row. Task cancelled.");
                future.cancel(true);
            }
        }
        log.debug("Finished waiting for batch of CSV row tasks.");
    }

    /**
     * Parses a single row from the CSV data into a CustomerRecord.
     * Uses Optional for handling potentially missing string values.
     */
    private Optional<CustomerRecord> parseRowToRecord(String[] rowData, int lineNumber, String createdBy, Queue<String> processingErrors, AtomicInteger failedRowCount) {
        try {
            if (rowData == null || rowData.length < MIN_EXPECTED_COLUMNS) {
                processingErrors.add("Line " + lineNumber + ": Incorrect number of columns. Expected at least " + MIN_EXPECTED_COLUMNS + ", found " + (rowData == null ? 0 : rowData.length));
                failedRowCount.incrementAndGet();
                return Optional.empty();
            }

            // --- Use Optional-returning getTrimmedString ---

            String customerId = getTrimmedString(rowData, COL_CUSTOMER_ID)
                    .orElseThrow(() -> new IllegalArgumentException("Customer ID is missing or empty"));

            // Use orElse("") for optional string fields, assuming empty string is acceptable
            String firstName = getTrimmedString(rowData, COL_FIRST_NAME).orElse("");
            String lastName = getTrimmedString(rowData, COL_LAST_NAME).orElse("");
            String company = getTrimmedString(rowData, COL_COMPANY).orElse("");
            String city = getTrimmedString(rowData, COL_CITY).orElse("");
            String country = getTrimmedString(rowData, COL_COUNTRY).orElse("");
            String phone1 = getTrimmedString(rowData, COL_PHONE_1).orElse("");
            String phone2 = getTrimmedString(rowData, COL_PHONE_2).orElse("");
            String email = getTrimmedString(rowData, COL_EMAIL).orElse("");
            String website = getTrimmedString(rowData, COL_WEBSITE).orElse("");

            // Use map on Optional string to parse date only if present
            LocalDate subscriptionDate = getTrimmedString(rowData, COL_SUBSCRIPTION_DATE)
                    .map(dateStr -> parseDate(dateStr, lineNumber)) // Call parseDate only if string is present
                    .orElse(null); // Result is null if date string was absent/blank

            // Create CustomerRecord
            CustomerRecord record = new CustomerRecord(
                    customerId, firstName, lastName, company, city, country,
                    phone1, phone2, email, subscriptionDate, website, createdBy
            );

            return Optional.of(record);

        } catch (IllegalArgumentException | DateTimeParseException dataEx) {
            processingErrors.add("Line " + lineNumber + ": Error parsing data - " + dataEx.getMessage());
            failedRowCount.incrementAndGet();
            log.warn("Data parsing error on CSV line {}: {}", lineNumber, dataEx.getMessage());
            return Optional.empty();
        } catch (ArrayIndexOutOfBoundsException aioobe) {
            processingErrors.add("Line " + lineNumber + ": Error accessing expected column (ArrayIndexOutOfBounds). Check CSV structure.");
            failedRowCount.incrementAndGet();
            log.warn("ArrayIndexOutOfBoundsException parsing CSV row {} - Check MIN_EXPECTED_COLUMNS and file structure.", lineNumber, aioobe);
            return Optional.empty();
        } catch (Exception e) {
            processingErrors.add("Line " + lineNumber + ": Unexpected error parsing row: " + e.getMessage());
            failedRowCount.incrementAndGet();
            log.error("Unexpected error parsing CSV row {}", lineNumber, e);
            return Optional.empty();
        }
    }

    /**
     * Safely gets a trimmed string value from the row data array.
     * Returns an Optional containing the trimmed string if the index is valid
     * and the value is non-null and non-blank after trimming.
     * Otherwise, returns Optional.empty().
     */
    private Optional<String> getTrimmedString(String[] rowData, int index) {
        if (index >= 0 && index < rowData.length && rowData[index] != null) {
            String trimmed = rowData[index].trim();
            return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
        }
        return Optional.empty();
    }

    /**
     * Parses a date string using primary and fallback formatters.
     * Assumes date is optional (returns null if input is null/blank/only quotes).
     * Throws DateTimeParseException if format is invalid after trying both formatters.
     */
    private LocalDate parseDate(String dateString, int lineNumber) throws DateTimeParseException {
        // This method is now only called if getTrimmedString provided a non-empty Optional string
        if (dateString == null || dateString.isBlank()) {
            // Should not happen if called via .map() from getTrimmedString result, but safe to keep.
            return null;
        }
        // Clean potential quotes often found in CSV and trim again
        String cleanDateString = dateString.trim().replace("\"", "").trim();
        if (cleanDateString.isEmpty()) {
            return null; // Return null if only quotes/whitespace were present
        }

        try {
            return LocalDate.parse(cleanDateString, PRIMARY_DATE_FORMATTER);
        } catch (DateTimeParseException e1) {
            try {
                log.trace("Primary date format failed for '{}' on CSV line {}, trying fallback.", cleanDateString, lineNumber);
                return LocalDate.parse(cleanDateString, FALLBACK_DATE_FORMATTER_MDY);
            } catch (DateTimeParseException e2) {
                throw new DateTimeParseException("Invalid date format for '" + cleanDateString + "'. Use yyyy-MM-dd or MM/dd/yyyy.", cleanDateString, e2.getErrorIndex());
            }
        }
    }
}