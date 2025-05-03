package com.wahid.multi_format_large_file_processor.service;

import com.wahid.multi_format_large_file_processor.entity.Customer;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ooxml.util.SAXHelper;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
// Extending AbstractItemCountingItemStreamItemReader handles ItemStream and basic state management
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;
import org.springframework.batch.item.ParseException;
import org.springframework.batch.item.UnexpectedInputException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.parsers.ParserConfigurationException; // Import needed for SAXHelper
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
// Extend AbstractItemCountingItemStreamItemReader for standard ItemStream behavior and state counting
public class ExcelStreamingItemReader extends AbstractItemCountingItemStreamItemReader<Customer> implements InitializingBean {

    private Resource resource;
    private final int headerRowsToSkip = 1; // Skip the first row (header)
    private boolean saveState = true; // Default Spring Batch behavior

    // Internal state for SAX parsing
    private OPCPackage opcPackage;
    private InputStream sheetInputStream;
    private XMLReader sheetParser;
    private BlockingQueue<Optional<String[]>> rowQueue;
    private final AtomicBoolean parsingFinished = new AtomicBoolean(false);
    private volatile Exception parsingException = null;
    private Thread parsingThread;
    private AtomicInteger currentRowInSheet = new AtomicInteger(0); // Track row index for skipping header

    // Constants for column indices (adjust if your Excel structure differs)
    // Assuming 0-based indexing matches the SAX handler's output array
    private static final int CUSTOMER_ID_COL = 1;
    private static final int FIRST_NAME_COL = 2;
    private static final int LAST_NAME_COL = 3;
    private static final int COMPANY_COL = 4;
    private static final int CITY_COL = 5;
    private static final int COUNTRY_COL = 6;
    private static final int PHONE1_COL = 7;
    private static final int PHONE2_COL = 8;
    private static final int EMAIL_COL = 9;
    private static final int SUBSCRIPTION_DATE_COL = 10;
    private static final int WEBSITE_COL = 11;
    private static final int EXPECTED_COL_COUNT = 12; // Number of columns expected in the array from handler

    // Date Formatters (should match BatchConfig or be passed in)
    private static final DateTimeFormatter PRIMARY_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter FALLBACK_DATE_FORMATTER_MDY = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    public ExcelStreamingItemReader() {
        // Set the name for the execution context key used by the parent class
        setName(ClassUtils.getShortName(ExcelStreamingItemReader.class));
    }


    public void setResource(Resource resource) {
        this.resource = resource;
    }

    /**
     * Called by Spring after properties are set.
     * Defers resource validation until open().
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        // Resource validation is moved to doOpen()
        log.debug("ExcelStreamingItemReader initialized (resource check deferred to open()).");
    }

    /**
     * Opens the Excel file using SAX event model and starts a background thread for parsing.
     * Resource validation happens here.
     */
    @Override
    protected void doOpen() throws Exception {
        // --- Resource Validation ---
        Assert.notNull(resource, "Input resource must be set before opening the reader.");
        Assert.state(resource.exists(), "Input resource must exist: " + resource);
        Assert.state(resource.isReadable(), "Input resource must be readable: " + resource);
        // --- End Resource Validation ---

        log.info("Opening Excel stream reader for resource: {}", resource.getFilename());
        parsingFinished.set(false);
        parsingException = null;
        currentRowInSheet.set(0); // Reset row counter for the sheet
        rowQueue = new LinkedBlockingQueue<>(200); // Buffer size for rows

        try {
            // Use try-with-resources for OPCPackage if possible, or ensure it's closed in doClose
            opcPackage = OPCPackage.open(resource.getInputStream());
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(opcPackage);
            XSSFReader xssfReader = new XSSFReader(opcPackage);
            StylesTable styles = xssfReader.getStylesTable();
            XSSFReader.SheetIterator iter = (XSSFReader.SheetIterator) xssfReader.getSheetsData();

            if (!iter.hasNext()) {
                log.warn("No sheets found in the Excel file: {}", resource.getFilename());
                parsingFinished.set(true); // No data to parse
                // No need to close opcPackage here if try-with-resources isn't used, handled in doClose
                return;
            }

            // Process only the first sheet
            sheetInputStream = iter.next(); // This stream needs careful closing in doClose
            InputSource sheetSource = new InputSource(sheetInputStream);
            sheetParser = SAXHelper.newXMLReader();

            // Custom handler to process rows and put them onto the queue
            ContentHandler handler = new XSSFSheetXMLHandler(
                    styles, strings, new SheetToCustomerRowHandler(rowQueue, currentRowInSheet, headerRowsToSkip), new DataFormatter(), false);
            sheetParser.setContentHandler(handler);

            // Start SAX parsing in a separate (virtual) thread
            parsingThread = Thread.ofVirtual().name("excel-sax-parser-", 0).start(() -> {
                try {
                    log.info("Starting SAX parsing for sheet...");
                    sheetParser.parse(sheetSource);
                    log.info("SAX parsing finished successfully.");
                } catch (Exception e) {
                    // Handle specific exceptions like SAXException, IOException
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        log.warn("SAX parsing thread interrupted.");
                    } else {
                        log.error("SAX Parsing thread failed for resource: {}", resource.getFilename(), e);
                        parsingException = e; // Store exception to be thrown by read()
                    }
                } finally {
                    parsingFinished.set(true);
                    // Add poison pill to unblock read() method if it's waiting
                    try {
                        // Use offer with timeout to prevent blocking indefinitely if queue is full
                        if (!rowQueue.offer(Optional.empty(), 5, TimeUnit.SECONDS)) {
                            log.error("Failed to add poison pill to queue after parsing finished.");
                            // Consider setting parsingException here if this is critical
                        } else {
                            log.debug("Poison pill added to queue.");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Interrupted while adding poison pill to queue.");
                    }
                }
            });

        } catch (Exception e) {
            // Ensure cleanup happens if open fails midway
            log.error("Failed during Excel stream opening process.", e);
            // Call doClose() to attempt cleanup, but catch exceptions from it
            try {
                doClose();
            } catch (Exception closeEx) {
                log.error("Exception during cleanup after open failure.", closeEx);
                e.addSuppressed(closeEx); // Add cleanup exception as suppressed
            }
            // Re-throw original exception wrapped in ItemStreamException
            throw new ItemStreamException("Failed to open Excel file: " + resource.getFilename(), e);
        }
    }

    /**
     * Reads the next Customer object by taking a row array from the internal queue.
     * Handles blocking, timeouts, poison pills, and parsing exceptions.
     */
    @Override
    protected Customer doRead() throws Exception {
        // No need to check streamOpen here, AbstractItemCountingItemStreamItemReader handles it

        Optional<String[]> rowOptional;
        try {
            // Poll with timeout to avoid blocking indefinitely if parser thread dies unexpectedly
            rowOptional = rowQueue.poll(1, TimeUnit.SECONDS);

            // Check if parser thread died with an exception *before* polling result check
            if (parsingException != null) {
                log.error("Background parsing failed previously.", parsingException);
                // Wrap and throw the stored exception
                Exception storedException = parsingException;
                parsingException = null; // Clear exception after throwing
                throw new Exception("Error during background Excel parsing", storedException);
            }

            // If timeout occurred but parsing is not finished, keep polling
            while (rowOptional == null && !parsingFinished.get()) {
                log.trace("Polling queue for Excel rows...");
                rowOptional = rowQueue.poll(1, TimeUnit.SECONDS);
                // Check for exception again after waking up
                if (parsingException != null) {
                    log.error("Background parsing failed while polling.", parsingException);
                    Exception storedException = parsingException;
                    parsingException = null; // Clear exception after throwing
                    throw new Exception("Error during background Excel parsing", storedException);
                }
            }

            // If still null after polling and parsing is finished, we are done
            if (rowOptional == null && parsingFinished.get()) {
                log.info("Finished reading from Excel file queue (queue empty, parsing done).");
                return null; // End of data
            }

            // If we received the poison pill (empty Optional), we are done
            if (rowOptional != null && rowOptional.isEmpty()) { // Check for empty Optional (poison pill)
                log.info("Received poison pill, finished reading.");
                parsingFinished.set(true); // Ensure flag is set
                return null; // End of data
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for row from queue. Returning null for this read.");
            return null; // Treat interrupt as end of data for this read attempt
        }

        // We have a valid row (present Optional), map it to Customer
        if (rowOptional != null && rowOptional.isPresent()) {
            String[] row = rowOptional.get();
            try {
                // AbstractItemCountingItemStreamItemReader increments count automatically
                return mapRowToCustomer(row);
            } catch (Exception e) {
                // Log mapping error and throw ParseException for skip logic
                // Include row number if available (currentRowInSheet might be slightly ahead)
                log.warn("Failed to map row near sheet index {} to Customer: {} - Error: {}",
                        currentRowInSheet.get(), (Object) row, e.getMessage());
                // Wrap the mapping exception in ParseException
                throw new ParseException("Failed to parse row data: " + e.getMessage(), e);
            }
        } else {
            // Should not happen if logic above is correct, but acts as safeguard
            log.warn("Row optional was null or empty unexpectedly after checks.");
            return null; // End of data
        }
    }

    /**
     * Closes the underlying Excel resources (OPCPackage, InputStream) and attempts
     * to ensure the parsing thread terminates gracefully.
     */
    @Override
    protected void doClose() throws Exception {
        log.info("Closing Excel stream reader for resource: {}", resource != null ? resource.getFilename() : "null");
        Exception caughtException = null;

        // 1. Attempt to interrupt the parser thread if it's still running
        //    This might help it exit faster if it's stuck in I/O or parsing
        if (parsingThread != null && parsingThread.isAlive()) {
            log.debug("Interrupting parsing thread...");
            parsingThread.interrupt();
        }

        // 2. Close the sheet InputStream
        try {
            if (sheetInputStream != null) {
                sheetInputStream.close();
                log.debug("Sheet input stream closed.");
            }
        } catch (IOException e) {
            log.warn("Error closing sheet input stream: {}", e.getMessage());
            caughtException = e; // Store first exception
        } finally {
            sheetInputStream = null;
        }

        // 3. Close the OPCPackage (this often closes associated streams too)
        try {
            if (opcPackage != null) {
                // Revert avoids writing changes, close is fine for read-only
                opcPackage.close();
                log.debug("OPC package closed.");
            }
        } catch (IOException e) {
            log.warn("Error closing OPC package: {}", e.getMessage());
            if (caughtException == null) caughtException = e;
            else caughtException.addSuppressed(e);
        } finally {
            opcPackage = null;
        }

        // 4. Wait briefly for the parsing thread to finish after closing resources/interrupting
        if (parsingThread != null) {
            try {
                log.debug("Waiting briefly for parsing thread to complete after interrupt/close...");
                parsingThread.join(2000); // Wait max 2 seconds
                if (parsingThread.isAlive()) {
                    log.warn("Parsing thread did not finish within timeout during close.");
                    // Thread might be un-interruptible or stuck, not much more we can do
                } else {
                    log.debug("Parsing thread finished.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for parsing thread to join during close.");
                // Don't store this as the primary caughtException unless nothing else failed
                if (caughtException == null) caughtException = e;
                else caughtException.addSuppressed(e);
            } finally {
                parsingThread = null;
            }
        }

        // 5. Clear the queue
        if (rowQueue != null) {
            rowQueue.clear();
            rowQueue = null; // Help GC
        }

        // Reset state flags
        parsingFinished.set(false); // Reset for potential reuse if open is called again
        parsingException = null;
        currentRowInSheet.set(0);

        log.info("Excel stream reader closed.");

        // If any exception occurred during close, throw it now
        if (caughtException != null) {
            throw new ItemStreamException("Exception(s) occurred during Excel reader close", caughtException);
        }
    }

    // Override setSaveState from AbstractItemCountingItemStreamItemReader
    @Override
    public void setSaveState(boolean saveState) {
        this.saveState = saveState;
        super.setSaveState(saveState);
    }

    // NOTE: update() and jumpToItem() for full restartability with SAX are complex.
    // The default update() from the parent class saves the item count.
    // Restoring requires re-parsing the file up to the saved item count, which
    // can be inefficient for large files. We'll rely on the parent's item count saving.
    // If more precise state (like exact sheet row) is needed, update() and jumpToItem()
    // would need custom implementation, potentially storing the row number and
    // modifying the SAX handler to skip rows until the target is reached during open().

    // --- Helper Methods ---

    // Maps a String array (row data) to a Customer object
    private Customer mapRowToCustomer(String[] row) throws DateTimeParseException, IllegalArgumentException {
        if (row.length < EXPECTED_COL_COUNT) {
            // Log the problematic row for debugging
            log.warn("Row has insufficient columns. Expected {}, got {}. Row data: {}", EXPECTED_COL_COUNT, row.length, java.util.Arrays.toString(row));
            throw new IllegalArgumentException("Row has " + row.length + " columns, expected at least " + EXPECTED_COL_COUNT);
        }

        Customer customer = new Customer();
        // Add null/blank checks if data quality is uncertain
        customer.setCustomerId(getTrimmedString(row, CUSTOMER_ID_COL));
        customer.setFirstName(getTrimmedString(row, FIRST_NAME_COL));
        customer.setLastName(getTrimmedString(row, LAST_NAME_COL));
        customer.setCompany(getTrimmedString(row, COMPANY_COL));
        customer.setCity(getTrimmedString(row, CITY_COL));
        customer.setCountry(getTrimmedString(row, COUNTRY_COL));
        customer.setPhone1(getTrimmedString(row, PHONE1_COL));
        customer.setPhone2(getTrimmedString(row, PHONE2_COL));
        customer.setEmail(getTrimmedString(row, EMAIL_COL));
        customer.setWebsite(getTrimmedString(row, WEBSITE_COL));
        customer.setCreatedAt(java.time.Instant.now()); // Set creation time during mapping

        // Handle date parsing
        String dateStr = getTrimmedString(row, SUBSCRIPTION_DATE_COL);
        if (dateStr != null && !dateStr.isBlank()) {
            try {
                customer.setSubscriptionDate(parseDate(dateStr)); // Use shared parsing logic
            } catch (DateTimeParseException e) {
                // Re-throw with more context if needed, or let it propagate
                log.warn("Failed to parse date '{}' for customerId [{}].", dateStr, customer.getCustomerId());
                throw e; // Propagate for skip logic
            }
        }

        return customer;
    }

    // Helper to get trimmed string value from row array safely
    private String getTrimmedString(String[] row, int index) {
        if (index >= 0 && index < row.length && row[index] != null) {
            return row[index].trim();
        }
        return null; // Return null if index out of bounds or value is null
    }

    // Date parsing helper method (consider moving to a shared utility class)
    private LocalDate parseDate(String cleanDateString) throws DateTimeParseException {
        try {
            // Handle potential Excel numeric date representation if needed (requires more complex logic)
            // For now, assume string formats
            return LocalDate.parse(cleanDateString, PRIMARY_DATE_FORMATTER);
        } catch (DateTimeParseException e1) {
            try {
                // Fallback format
                return LocalDate.parse(cleanDateString, FALLBACK_DATE_FORMATTER_MDY);
            } catch (DateTimeParseException e2) {
                // If both fail, add context and rethrow the first exception
                e1.addSuppressed(e2);
                throw new DateTimeParseException("Failed to parse date '" + cleanDateString + "' with available formats.", cleanDateString, 0, e1);
            }
        }
    }


    /**
     * SAX Content Handler to process rows from the sheet and put them onto a queue.
     */
    private static class SheetToCustomerRowHandler implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final BlockingQueue<Optional<String[]>> outputQueue;
        private final AtomicInteger sheetRowCounter; // Counter passed from reader
        private final int rowsToSkip;
        private String[] currentRowData;
        private int currentCellIndex = -1; // Tracks index within the currentRowData array

        SheetToCustomerRowHandler(BlockingQueue<Optional<String[]>> outputQueue, AtomicInteger sheetRowCounter, int rowsToSkip) {
            this.outputQueue = outputQueue;
            this.sheetRowCounter = sheetRowCounter;
            this.rowsToSkip = rowsToSkip;
        }

        @Override
        public void startRow(int rowNum) {
            // Update the shared counter (used mainly for logging/debugging in reader)
            this.sheetRowCounter.set(rowNum);

            if (rowNum >= rowsToSkip) {
                // Allocate array size based on expected columns
                this.currentRowData = new String[EXPECTED_COL_COUNT];
                this.currentCellIndex = -1; // Reset cell index for the new row
            } else {
                this.currentRowData = null; // Indicate row should be skipped
                log.trace("Skipping header row {}", rowNum);
            }
        }

        @Override
        public void endRow(int rowNum) {
            if (currentRowData != null && rowNum >= rowsToSkip) {
                try {
                    // Put the completed row onto the queue
                    // Use offer with timeout to prevent blocking indefinitely
                    if (!outputQueue.offer(Optional.of(currentRowData), 5, TimeUnit.SECONDS)) {
                        log.error("Failed to queue row {} within timeout. Queue may be full or reader thread unresponsive.", rowNum);
                        // How to handle this? Options:
                        // 1. Throw a RuntimeException to stop the parsing thread (will be caught by thread's handler)
                        // 2. Log and continue (might lose data if reader never catches up)
                        // 3. Retry logic (complex)
                        // For now, log error. Consider throwing if data loss is unacceptable.
                        // throw new RuntimeException("Failed to queue row " + rowNum);
                    } else {
                        log.trace("Queued row {}", rowNum);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("SAX Handler interrupted while queueing row {}.", rowNum);
                    // Stop processing further rows in this thread
                    throw new RuntimeException("SAX Handler interrupted", e);
                }
            }
            // Reset for next row
            currentRowData = null;
            currentCellIndex = -1;
        }

        @Override
        public void cell(String cellReference, String formattedValue, org.apache.poi.xssf.usermodel.XSSFComment comment) {
            if (currentRowData == null) return; // Skipping header or row outside range

            // Determine the 0-based column index from the cell reference (e.g., A, B, C...)
            currentCellIndex = getColumnIndexFromCellRef(cellReference);

            if (currentCellIndex >= 0 && currentCellIndex < currentRowData.length) {
                // Store the formatted value in the correct position
                currentRowData[currentCellIndex] = formattedValue;
            } else {
                // Log if a cell is encountered beyond the expected column count
                if (currentCellIndex >= currentRowData.length) {
                    log.trace("Ignoring cell {} outside expected column range (expected max index {}, got {}).",
                            cellReference, currentRowData.length - 1, currentCellIndex);
                }
                // If index is negative (error in getColumnIndexFromCellRef), log that too
                else if (currentCellIndex < 0) {
                    log.warn("Invalid negative column index calculated for cell reference: {}", cellReference);
                }
            }
        }

        // Helper to convert Excel column name (A, B, ..., Z, AA, AB,...) to 0-based index
        private int getColumnIndexFromCellRef(String cellReference) {
            if (cellReference == null || cellReference.isEmpty()) {
                return -1;
            }
            // Extract the column letters part (remove digits)
            String colRef = cellReference.replaceAll("[0-9]", "");
            if (colRef.isEmpty()) {
                return -1; // Should not happen for valid cell refs like "A1"
            }

            int index = 0;
            for (int i = 0; i < colRef.length(); i++) {
                char c = colRef.charAt(i);
                // Basic validation for uppercase letters
                if (c < 'A' || c > 'Z') return -1;
                index = index * 26 + (c - 'A' + 1);
            }
            return index - 1; // Return 0-based index
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // Default behavior: Ignore headers/footers within the sheet data processing
        }

        // Implement other methods from SheetContentsHandler if needed (usually not required)
        // public void cell(String cellReference, String formattedValue) {} // Deprecated version
        // public void hyperlink(String cellReference, String url, String text) {}
        // public void comment(String cellReference, String author, String text) {}
    }
}