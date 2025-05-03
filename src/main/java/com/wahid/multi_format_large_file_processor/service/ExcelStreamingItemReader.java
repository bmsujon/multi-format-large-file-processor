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
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ParseException;
import org.springframework.batch.item.UnexpectedInputException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

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
public class ExcelStreamingItemReader implements ItemStreamReader<Customer>, InitializingBean {

    private Resource resource;
    private final int headerRowsToSkip = 1; // Skip the first row (header)

    // Internal state
    private OPCPackage opcPackage;
    private InputStream sheetInputStream;
    private XMLReader sheetParser;
    private BlockingQueue<Optional<String[]>> rowQueue;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);
    private final AtomicBoolean parsingFinished = new AtomicBoolean(false);
    private volatile Exception parsingException = null;
    private Thread parsingThread;
    private AtomicInteger currentRowIndex = new AtomicInteger(0); // Track row index for skipping header

    // Constants for column indices (adjust if your Excel structure differs)
    private static final int INDEX_COL = 0;
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
    private static final int EXPECTED_COL_COUNT = 12; // Number of columns expected

    // Date Formatters (should match BatchConfig or be passed in)
    private static final DateTimeFormatter PRIMARY_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter FALLBACK_DATE_FORMATTER_MDY = DateTimeFormatter.ofPattern("MM/dd/yyyy");


    public void setResource(Resource resource) {
        this.resource = resource;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        Assert.notNull(resource, "Input resource must be set");
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        if (!streamOpen.compareAndSet(false, true)) {
            log.warn("Stream already marked as open. Ignoring open call.");
            return; // Already open
        }
        log.info("Opening Excel stream reader for resource: {}", resource.getFilename());
        parsingFinished.set(false);
        parsingException = null;
        currentRowIndex.set(0);
        rowQueue = new LinkedBlockingQueue<>(200); // Buffer size for rows

        try {
            opcPackage = OPCPackage.open(resource.getInputStream());
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(opcPackage);
            XSSFReader xssfReader = new XSSFReader(opcPackage);
            StylesTable styles = xssfReader.getStylesTable();
            XSSFReader.SheetIterator iter = (XSSFReader.SheetIterator) xssfReader.getSheetsData();

            if (!iter.hasNext()) {
                log.warn("No sheets found in the Excel file: {}", resource.getFilename());
                parsingFinished.set(true); // No data to parse
                return;
            }

            // Process only the first sheet
            sheetInputStream = iter.next();
            InputSource sheetSource = new InputSource(sheetInputStream);
            sheetParser = SAXHelper.newXMLReader();

            // Custom handler to process rows and put them onto the queue
            ContentHandler handler = new XSSFSheetXMLHandler(
                    styles, strings, new SheetToCustomerRowHandler(rowQueue, currentRowIndex, headerRowsToSkip), new DataFormatter(), false);
            sheetParser.setContentHandler(handler);

            // Start SAX parsing in a separate (virtual) thread
            parsingThread = Thread.ofVirtual().name("excel-sax-parser-", 0).start(() -> {
                try {
                    log.info("Starting SAX parsing for sheet...");
                    sheetParser.parse(sheetSource);
                    log.info("SAX parsing finished successfully.");
                } catch (Exception e) {
                    log.error("SAX Parsing thread failed for resource: {}", resource.getFilename(), e);
                    parsingException = e; // Store exception to be thrown by read()
                } finally {
                    parsingFinished.set(true);
                    // Add poison pill to unblock read() method if it's waiting
                    try {
                        if (!rowQueue.offer(Optional.empty(), 5, TimeUnit.SECONDS)) {
                             log.error("Failed to add poison pill to queue after parsing finished.");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Interrupted while adding poison pill to queue.");
                    }
                }
            });

        } catch (Exception e) {
            closeSilently(); // Attempt cleanup on open failure
            throw new ItemStreamException("Failed to open Excel file: " + resource.getFilename(), e);
        }
    }

    @Override
    public Customer read() throws Exception, UnexpectedInputException, ParseException {
        if (!streamOpen.get()) {
            throw new IllegalStateException("Reader must be opened before reading.");
        }

        Optional<String[]> rowOptional;
        try {
            // Poll with timeout to avoid blocking indefinitely if parser thread dies unexpectedly
            // Adjust timeout as needed
            rowOptional = rowQueue.poll(1, TimeUnit.SECONDS);

            // Check if parser thread died with an exception
            if (parsingException != null) {
                throw new Exception("Error during background Excel parsing", parsingException);
            }

            // If timeout occurred but parsing is not finished, keep polling
            while (rowOptional == null && !parsingFinished.get()) {
                 // Check for exception again after waking up
                 if (parsingException != null) {
                    throw new Exception("Error during background Excel parsing", parsingException);
                 }
                 log.trace("Polling queue for Excel rows...");
                 rowOptional = rowQueue.poll(1, TimeUnit.SECONDS);
            }

            // If still null after polling and parsing is finished, we are done
            if (rowOptional == null && parsingFinished.get()) {
                log.info("Finished reading from Excel file queue.");
                return null;
            }

            // If we received the poison pill (empty Optional), we are done
            if (rowOptional != null && !rowOptional.isPresent()) {
                log.info("Received poison pill, finished reading.");
                parsingFinished.set(true); // Ensure flag is set
                return null;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for row from queue.");
            return null; // Treat interrupt as end of data for this read attempt
        }

        // We have a row, map it to Customer
        if (rowOptional != null) {
             String[] row = rowOptional.get();
             try {
                 return mapRowToCustomer(row);
             } catch (Exception e) {
                 // Log mapping error and potentially throw ParseException for skip logic
                 log.warn("Failed to map row to Customer: {} - Error: {}", (Object) row, e.getMessage());
                 throw new ParseException("Failed to parse row: " + e.getMessage(), e);
             }
        } else {
             // Should not happen if logic above is correct, but acts as safeguard
             log.warn("Row optional was null unexpectedly.");
             return null;
        }
    }

    @Override
    public void close() throws ItemStreamException {
        if (!streamOpen.compareAndSet(true, false)) {
            log.warn("Stream already marked as closed or was never opened. Ignoring close call.");
            return; // Already closed or never opened
        }
        log.info("Closing Excel stream reader for resource: {}", resource.getFilename());
        closeSilently();

        // Wait briefly for the parsing thread to finish if it hasn't already
        if (parsingThread != null && parsingThread.isAlive()) {
            try {
                log.debug("Waiting briefly for parsing thread to complete...");
                parsingThread.join(2000); // Wait max 2 seconds
                if (parsingThread.isAlive()) {
                    log.warn("Parsing thread did not finish within timeout during close. Attempting interrupt.");
                    parsingThread.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for parsing thread to join.");
            }
        }
        rowQueue = null; // Help GC
        parsingThread = null;
        log.info("Excel stream reader closed.");
    }

    private void closeSilently() {
        try {
            if (sheetInputStream != null) {
                sheetInputStream.close();
                sheetInputStream = null;
            }
        } catch (IOException e) {
            log.warn("Error closing sheet input stream: {}", e.getMessage());
        }
        try {
            if (opcPackage != null) {
                // Closing opcPackage also closes associated streams usually
                opcPackage.close();
                opcPackage = null;
            }
        } catch (IOException e) {
            log.warn("Error closing OPC package: {}", e.getMessage());
        }
    }


    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // For restartability: Store the current row index or other relevant state
        // executionContext.putInt("excel.current.row", currentRowIndex.get());
        // Note: True restartability with SAX is complex as you can't easily seek.
        // Usually requires re-parsing up to the last committed point.
        log.debug("Update called - state persistence not fully implemented for SAX restart.");
    }

    // Maps a String array (row data) to a Customer object
    private Customer mapRowToCustomer(String[] row) throws DateTimeParseException, IllegalArgumentException {
        if (row.length < EXPECTED_COL_COUNT) {
            throw new IllegalArgumentException("Row has " + row.length + " columns, expected at least " + EXPECTED_COL_COUNT);
        }

        Customer customer = new Customer();
        // Assuming data is clean; add null/blank checks as needed
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

        // Handle date parsing
        String dateStr = getTrimmedString(row, SUBSCRIPTION_DATE_COL);
        if (dateStr != null && !dateStr.isBlank()) {
            customer.setSubscriptionDate(parseDate(dateStr)); // Use shared parsing logic
        }

        return customer;
    }

    // Helper to get trimmed string value from row array safely
    private String getTrimmedString(String[] row, int index) {
        if (index >= 0 && index < row.length && row[index] != null) {
            return row[index].trim();
        }
        return null;
    }

    // Date parsing helper method (duplicate from BatchConfig - consider moving to a shared utility)
    private LocalDate parseDate(String cleanDateString) throws DateTimeParseException {
        try {
            // Handle potential Excel numeric date representation if needed (requires more complex logic)
            return LocalDate.parse(cleanDateString, PRIMARY_DATE_FORMATTER);
        } catch (DateTimeParseException e1) {
            try {
                 return LocalDate.parse(cleanDateString, FALLBACK_DATE_FORMATTER_MDY);
            } catch (DateTimeParseException e2) {
                 // If both fail, rethrow the first exception or a combined one
                 throw e1;
            }
        }
    }


    /**
     * SAX Content Handler to process rows from the sheet and put them onto a queue.
     */
    private static class SheetToCustomerRowHandler implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final BlockingQueue<Optional<String[]>> outputQueue;
        private final AtomicInteger rowIndex;
        private final int rowsToSkip;
        private String[] currentRow;
        private int currentCol = -1;

        SheetToCustomerRowHandler(BlockingQueue<Optional<String[]>> outputQueue, AtomicInteger rowIndexCounter, int rowsToSkip) {
            this.outputQueue = outputQueue;
            this.rowIndex = rowIndexCounter;
            this.rowsToSkip = rowsToSkip;
        }

        @Override
        public void startRow(int rowNum) {
            this.rowIndex.set(rowNum);
            if (rowNum >= rowsToSkip) {
                // Allocate array size based on expected columns, adjust if needed
                this.currentRow = new String[EXPECTED_COL_COUNT];
                this.currentCol = -1;
            } else {
                this.currentRow = null; // Indicate row should be skipped
            }
        }

        @Override
        public void endRow(int rowNum) {
            if (currentRow != null && rowNum >= rowsToSkip) {
                try {
                    // Put the completed row onto the queue
                    if (!outputQueue.offer(Optional.of(currentRow), 5, TimeUnit.SECONDS)) {
                         log.error("Failed to queue row {} within timeout.", rowNum);
                         // Handle queue full scenario - maybe throw exception?
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while queueing row {}.", rowNum);
                }
            }
            // Reset for next row
            currentRow = null;
            currentCol = -1;
        }

        @Override
        public void cell(String cellReference, String formattedValue, org.apache.poi.xssf.usermodel.XSSFComment comment) {
            if (currentRow == null) return; // Skipping header or row outside range

            // Get column index from reference (e.g., A1, B1)
            currentCol = getColumnIndex(cellReference);

            if (currentCol >= 0 && currentCol < currentRow.length) {
                currentRow[currentCol] = formattedValue;
            } else {
                 log.trace("Ignoring cell {} outside expected column range.", cellReference);
            }
        }

        // Basic conversion from Excel column name (A, B, ..., Z, AA, AB,...) to 0-based index
        private int getColumnIndex(String cellReference) {
             if (cellReference == null || cellReference.isEmpty()) {
                 return -1;
             }
             String colRef = cellReference.replaceAll("[0-9]", "");
             int index = 0;
             for (int i = 0; i < colRef.length(); i++) {
                 index = index * 26 + (colRef.charAt(i) - 'A' + 1);
             }
             return index - 1; // 0-based
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // Ignore headers/footers for data rows
        }
    }
}