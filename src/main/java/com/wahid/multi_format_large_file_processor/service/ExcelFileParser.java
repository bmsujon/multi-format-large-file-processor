package com.wahid.multi_format_large_file_processor.service;

import com.wahid.multi_format_large_file_processor.dto.CustomerRecord;
import com.wahid.multi_format_large_file_processor.dto.ParseResult;
import org.apache.poi.ooxml.util.SAXHelper;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component("excel")
public class ExcelFileParser implements FileParser {

    private static final Logger log = LoggerFactory.getLogger(ExcelFileParser.class);

    // --- Configuration ---
    private static final int PARALLEL_BATCH_SIZE = 200;
    private static final long FUTURE_GET_TIMEOUT_SECONDS = 60;
    private static final int EXPECTED_COLUMN_COUNT = 12; // Number of columns expected (0-indexed: 0 to 11)

    // --- Date Formatting ---
    private static final DateTimeFormatter PRIMARY_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE; // yyyy-MM-dd
    private static final DateTimeFormatter FALLBACK_DATE_FORMATTER_MDY = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private final ExecutorService virtualThreadExecutor;

    @Autowired
    public ExcelFileParser(@Qualifier("virtualThreadExecutor") ExecutorService virtualThreadExecutor) {
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    @Override
    public String getSupportedFileType() {
        return "excel";
    }

    @Override
    public ParseResult parse(InputStream inputStream, String createdBy) throws IOException {
        Queue<CustomerRecord> validCustomersQueue = new ConcurrentLinkedQueue<>();
        Queue<String> processingErrorsQueue = new ConcurrentLinkedQueue<>();
        AtomicInteger processedRowCount = new AtomicInteger(0);
        AtomicInteger failedRowCount = new AtomicInteger(0);
        final List<Future<?>> futures = Collections.synchronizedList(new ArrayList<>(PARALLEL_BATCH_SIZE));

        log.info("Starting Excel parsing using SAX event model.");

        try (OPCPackage opcPackage = OPCPackage.open(inputStream)) {
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(opcPackage);
            XSSFReader xssfReader = new XSSFReader(opcPackage);
            StylesTable styles = xssfReader.getStylesTable();
            XSSFReader.SheetIterator iter = (XSSFReader.SheetIterator) xssfReader.getSheetsData();

            if (iter.hasNext()) {
                log.debug("Processing first sheet found in the Excel file.");
                try (InputStream stream = iter.next()) {
                    InputSource sheetSource = new InputSource(stream);
                    XMLReader sheetParser = SAXHelper.newXMLReader();

                    SheetToCustomerProcessor processor = new SheetToCustomerProcessor(
                            validCustomersQueue,
                            processingErrorsQueue,
                            processedRowCount,
                            failedRowCount,
                            futures,
                            createdBy,
                            virtualThreadExecutor,
                            this
                    );

                    ContentHandler handler = new XSSFSheetXMLHandler(
                            styles,
                            strings,
                            processor,
                            new DataFormatter(), // Use DataFormatter for cell value interpretation
                            false
                    );
                    sheetParser.setContentHandler(handler);
                    sheetParser.parse(sheetSource);
                    log.debug("Finished parsing sheet data.");
                }
            } else {
                log.warn("Excel file contains no sheets.");
                processingErrorsQueue.add("Excel file contains no sheets.");
            }

            log.debug("Waiting for final batch of row processing tasks to complete.");
            waitForFutures(futures, processingErrorsQueue);

        } catch (IOException ioex) {
            log.error("I/O error during Excel streaming", ioex);
            processingErrorsQueue.add("I/O error reading Excel file: " + ioex.getMessage());
            validCustomersQueue.clear();
            failedRowCount.set(processedRowCount.get() + failedRowCount.get());
            processedRowCount.set(0);
            throw ioex;
        } catch (SAXException saxEx) {
            log.error("SAX parsing error during Excel streaming", saxEx);
            processingErrorsQueue.add("Error parsing Excel file structure: " + saxEx.getMessage());
            validCustomersQueue.clear();
            failedRowCount.set(processedRowCount.get() + failedRowCount.get());
            processedRowCount.set(0);
            throw new IOException("Failed to parse Excel file structure: " + saxEx.getMessage(), saxEx);
        } catch (Exception e) {
            log.error("Unexpected error processing Excel file via streaming", e);
            processingErrorsQueue.add("Unexpected Excel processing error: " + e.getMessage());
            validCustomersQueue.clear();
            failedRowCount.set(processedRowCount.get() + failedRowCount.get());
            processedRowCount.set(0);
            throw new IOException("Failed to process Excel file: " + e.getMessage(), e);
        } finally {
            futures.clear();
        }

        log.info("Excel parsing finished. Processed: {}, Failed: {}, Valid Records Found: {}",
                processedRowCount.get(), failedRowCount.get(), validCustomersQueue.size());

        return new ParseResult(
                new ArrayList<>(validCustomersQueue),
                new ArrayList<>(processingErrorsQueue),
                processedRowCount.get(),
                failedRowCount.get()
        );
    }

    /**
     * Waits for the submitted row processing tasks to complete and handles processingErrors.
     * Clears the futures list after processing.
     */
    void waitForFutures(List<Future<?>> futures, Queue<String> processingErrorsQueue) {
        if (futures.isEmpty()) {
            return;
        }
        log.debug("Waiting for {} row processing tasks...", futures.size());
        List<Future<?>> futuresToProcess = new ArrayList<>(futures);
        futures.clear(); // Clear the original list

        for (Future<?> future : futuresToProcess) {
            try {
                future.get(FUTURE_GET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Row processing task interrupted.", ie);
                processingErrorsQueue.add("Row processing was interrupted.");
            } catch (ExecutionException ee) {
                log.error("Error executing row processing task.", ee.getCause());
                processingErrorsQueue.add("Internal error during row processing: " + (ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage()));
            } catch (TimeoutException te) {
                log.error("Timeout waiting for row processing task to complete after {} seconds.", FUTURE_GET_TIMEOUT_SECONDS);
                processingErrorsQueue.add("Timeout processing a row (potential complex data or deadlock).");
                future.cancel(true);
            }
        }
        log.debug("Finished waiting for batch of row tasks.");
    }

    // --- Nested Class for SAX Processing ---
    private static class SheetToCustomerProcessor implements XSSFSheetXMLHandler.SheetContentsHandler {

        // Define column indices (0-based)
        private static final int COL_CUSTOMER_ID = 0;
        private static final int COL_FIRST_NAME = 1;
        private static final int COL_LAST_NAME = 2;
        private static final int COL_COMPANY = 3;
        private static final int COL_CITY = 4;
        private static final int COL_COUNTRY = 5;
        private static final int COL_PHONE_1 = 6;
        private static final int COL_PHONE_2 = 7;
        private static final int COL_EMAIL = 8;
        private static final int COL_SUBSCRIPTION_DATE = 9;
        private static final int COL_WEBSITE = 10;
        // Assuming index 11 is the last expected column based on EXPECTED_COLUMN_COUNT = 12

        // --- Dependencies ---
        private final Queue<CustomerRecord> validCustomersQueue;
        private final Queue<String> processingErrorsQueue;
        private final AtomicInteger processedRowCount;
        private final AtomicInteger failedRowCount;
        private final List<Future<?>> futures;
        private final String createdBy;
        private final ExecutorService virtualThreadExecutor;
        private final ExcelFileParser parentParser;

        // --- SAX State ---
        private boolean isFirstRow = true;
        private int currentRowNum = -1;
        private int currentColNum = -1;
        private String[] currentRowData;

        SheetToCustomerProcessor(Queue<CustomerRecord> validCustomersQueue,
                                 Queue<String> processingErrorsQueue,
                                 AtomicInteger processedRowCount,
                                 AtomicInteger failedRowCount,
                                 List<Future<?>> futures,
                                 String createdBy,
                                 ExecutorService virtualThreadExecutor,
                                 ExcelFileParser parentParser) {
            this.validCustomersQueue = validCustomersQueue;
            this.processingErrorsQueue = processingErrorsQueue;
            this.processedRowCount = processedRowCount;
            this.failedRowCount = failedRowCount;
            this.futures = futures;
            this.createdBy = createdBy;
            this.virtualThreadExecutor = virtualThreadExecutor;
            this.parentParser = parentParser;
            this.currentRowData = new String[EXPECTED_COLUMN_COUNT];
        }

        @Override
        public void startRow(int rowNum) {
            this.currentRowNum = rowNum;
            this.currentColNum = -1;
            Arrays.fill(this.currentRowData, null);
            if (rowNum == 0) {
                isFirstRow = true;
                log.trace("Processing header row (skipped).");
            } else {
                isFirstRow = false;
                log.trace("Starting processing data row {}", rowNum + 1);
            }
        }

        @Override
        public void endRow(int rowNum) {
            if (!isFirstRow && currentRowNum == rowNum) {
                processedRowCount.incrementAndGet();
                final int finalRowNumForError = currentRowNum + 1;
                final String[] rowDataCopy = Arrays.copyOf(currentRowData, currentRowData.length);

                futures.add(virtualThreadExecutor.submit(() -> {
                    try {
                        // Use the updated parsing method
                        Optional<CustomerRecord> customerOpt = parseRowToRecord(rowDataCopy, finalRowNumForError, createdBy, processingErrorsQueue, failedRowCount);
                        customerOpt.ifPresent(validCustomersQueue::add);
                    } catch (Exception taskEx) {
                        log.error("Unexpected error in row processing task for line {}: {}", finalRowNumForError, taskEx.getMessage(), taskEx);
                        processingErrorsQueue.add("Line " + finalRowNumForError + ": Unexpected internal error during processing: " + taskEx.getMessage());
                        failedRowCount.incrementAndGet();
                    }
                }));

                if (futures.size() >= PARALLEL_BATCH_SIZE) {
                    log.debug("Reached batch size ({}), waiting for tasks...", PARALLEL_BATCH_SIZE);
                    parentParser.waitForFutures(futures, processingErrorsQueue);
                }
            }
            this.currentRowNum = -1;
            this.currentColNum = -1;
        }

        @Override
        public void cell(String cellReference, String formattedValue, org.apache.poi.xssf.usermodel.XSSFComment comment) {
            currentColNum++;
            if (currentColNum < EXPECTED_COLUMN_COUNT) {
                // Store the formatted value provided by XSSFSheetXMLHandler
                // DataFormatter handles dates, numbers etc. based on cell style
                currentRowData[currentColNum] = formattedValue;
                log.trace("Cell {}: {}", cellReference, formattedValue);
            } else {
                log.trace("Ignoring cell {} as it exceeds expected column count {}", cellReference, EXPECTED_COLUMN_COUNT);
            }
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // Not used
        }

        // --- Row Parsing Logic ---

        /**
         * Parses a single row's data into a CustomerRecord using Optional.
         */
        private Optional<CustomerRecord> parseRowToRecord(String[] rowData, int lineNumber, String createdBy, Queue<String> processingErrors, AtomicInteger failedRowCount) {
            try {
                // Basic check: Ensure we have enough columns collected
                // Note: The cell method already limits collection up to EXPECTED_COLUMN_COUNT
                if (rowData == null) { // Should not happen, but defensive check
                    processingErrors.add("Line " + lineNumber + ": Internal error - row data is null.");
                    failedRowCount.incrementAndGet();
                    return Optional.empty();
                }

                // --- Use Optional-returning getTrimmedString ---

                String customerId = getTrimmedString(rowData, COL_CUSTOMER_ID)
                        .orElseThrow(() -> new IllegalArgumentException("Customer ID is missing or empty"));

                // Use orElse("") for optional string fields
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
                log.warn("Data parsing error on Excel line {}: {}", lineNumber, dataEx.getMessage());
                return Optional.empty();
            } catch (ArrayIndexOutOfBoundsException aioobe) {
                // Should be less likely now due to how data is collected, but keep as safeguard
                processingErrors.add("Line " + lineNumber + ": Error accessing expected column index. Check Excel structure/constants.");
                failedRowCount.incrementAndGet();
                log.warn("ArrayIndexOutOfBoundsException parsing Excel row {} - Check constants and structure.", lineNumber, aioobe);
                return Optional.empty();
            } catch (Exception e) {
                processingErrors.add("Line " + lineNumber + ": Unexpected error parsing row: " + e.getMessage());
                failedRowCount.incrementAndGet();
                log.error("Unexpected error parsing Excel row {}", lineNumber, e);
                return Optional.empty();
            }
        }

        /**
         * Safely gets a trimmed string value from the row data array using Optional.
         * Returns Optional.empty() if index is invalid, value is null, or value is blank after trimming.
         */
        private Optional<String> getTrimmedString(String[] rowData, int index) {
            if (index >= 0 && index < rowData.length && rowData[index] != null) {
                String trimmed = rowData[index].trim();
                // Treat empty strings after trimming as absent
                return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
            }
            return Optional.empty();
        }

        /**
         * Parses a date string using primary and fallback formatters.
         * Assumes date is optional (returns null if input is null/blank).
         * Throws DateTimeParseException if format is invalid after trying both formatters.
         */
        private LocalDate parseDate(String dateString, int lineNumber) throws DateTimeParseException {
            // This method is now only called if getTrimmedString provided a non-empty Optional string
            if (dateString == null || dateString.isBlank()) {
                // Should not happen if called via .map(), but safe to keep.
                return null;
            }
            // Excel formatted dates might not have quotes, but trim anyway
            String cleanDateString = dateString.trim();
            if (cleanDateString.isEmpty()) {
                return null;
            }

            try {
                // Try primary format (e.g., yyyy-MM-dd)
                return LocalDate.parse(cleanDateString, PRIMARY_DATE_FORMATTER);
            } catch (DateTimeParseException e1) {
                try {
                    // Try fallback format (e.g., MM/dd/yyyy)
                    // DataFormatter might already format dates like this, but try parsing anyway
                    log.trace("Primary date format failed for '{}' on Excel line {}, trying fallback.", cleanDateString, lineNumber);
                    return LocalDate.parse(cleanDateString, FALLBACK_DATE_FORMATTER_MDY);
                } catch (DateTimeParseException e2) {
                    // If both fail, throw an exception
                    throw new DateTimeParseException("Invalid date format for '" + cleanDateString + "'. Use yyyy-MM-dd or MM/dd/yyyy.", cleanDateString, e2.getErrorIndex());
                }
            }
        }

    } // End of SheetToCustomerProcessor
}