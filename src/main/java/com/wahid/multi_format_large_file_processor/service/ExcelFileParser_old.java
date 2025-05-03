//package com.wahid.multi_format_large_file_processor.service;
//
//import com.wahid.multi_format_large_file_processor.entity.Customer;
//import jakarta.validation.ConstraintViolation;
//import jakarta.validation.Validator;
//import org.apache.poi.ooxml.util.SAXHelper;
//import org.apache.poi.openxml4j.opc.OPCPackage;
//import org.apache.poi.ss.usermodel.DataFormatter;
//import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
//import org.apache.poi.xssf.eventusermodel.XSSFReader;
//import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
//import org.apache.poi.xssf.model.StylesTable;
//import org.apache.poi.xssf.usermodel.XSSFComment;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.beans.factory.annotation.Qualifier;
//import org.springframework.stereotype.Component;
//import org.xml.sax.ContentHandler;
//import org.xml.sax.InputSource;
//import org.xml.sax.XMLReader;
//
//import java.io.IOException;
//import java.io.InputStream;
//import java.time.LocalDate;
//import java.time.format.DateTimeFormatter;
//import java.time.format.DateTimeParseException;
//import java.util.*;
//import java.util.concurrent.*;
//import java.util.concurrent.atomic.AtomicInteger;
//
//@Component("excel") // Bean name matches the file type identifier
//public class ExcelFileParser_old implements FileParser {
//
//    private static final Logger log = LoggerFactory.getLogger(ExcelFileParser_old.class);
//    private static final int PARALLEL_BATCH_SIZE = 200; // How many rows to process in parallel threads
//    private static final long FUTURE_GET_TIMEOUT_SECONDS = 60; // Timeout for waiting on a single row task
//    private static final int EXPECTED_COLUMN_COUNT = 12; // Number of columns expected (0-11)
//
//    // Primary Date Formatter
//    private static final DateTimeFormatter PRIMARY_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE; // yyyy-MM-dd
//    // Optional: Add fallback formatters if needed
//    private static final DateTimeFormatter FALLBACK_DATE_FORMATTER_MDY = DateTimeFormatter.ofPattern("MM/dd/yyyy");
//
//
//    @Autowired
//    private Validator validator;
//
//    @Autowired
//    @Qualifier("virtualThreadExecutor")
//    private ExecutorService virtualThreadExecutor;
//
//    @Override
//    public String getSupportedFileType() {
//        return "excel";
//    }
//
//    @Override
//    public ParseResult parse(InputStream inputStream, String createdBy) throws IOException {
//        Queue<Customer> validCustomersQueue = new ConcurrentLinkedQueue<>();
//        Queue<String> processingErrorsQueue = new ConcurrentLinkedQueue<>();
//        AtomicInteger processedRowCount = new AtomicInteger(0);
//        AtomicInteger failedRowCount = new AtomicInteger(0);
//        List<Future<?>> futures = new ArrayList<>(PARALLEL_BATCH_SIZE);
//
//        try (OPCPackage opcPackage = OPCPackage.open(inputStream)) {
//            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(opcPackage);
//            XSSFReader xssfReader = new XSSFReader(opcPackage);
//            StylesTable styles = xssfReader.getStylesTable();
//            XSSFReader.SheetIterator iter = (XSSFReader.SheetIterator) xssfReader.getSheetsData();
//
//            if (iter.hasNext()) {
//                try (InputStream stream = iter.next()) {
//                    InputSource sheetSource = new InputSource(stream);
//                    XMLReader sheetParser = SAXHelper.newXMLReader(); // Can throw ParserConfigurationException, SAXException
//
//                    ContentHandler handler = new XSSFSheetXMLHandler(
//                            styles,
//                            strings,
//                            new SheetToCustomerProcessor(
//                                    validCustomersQueue,
//                                    processingErrorsQueue,
//                                    processedRowCount,
//                                    failedRowCount,
//                                    futures,
//                                    createdBy,
//                                    validator,
//                                    virtualThreadExecutor,
//                                    this
//                            ),
//                            new DataFormatter(),
//                            false
//                    );
//                    sheetParser.setContentHandler(handler);
//                    sheetParser.parse(sheetSource); // Can throw SAXException, IOException
//                }
//            } else {
//                processingErrorsQueue.add("Excel file contains no sheets.");
//            }
//
//            waitForFutures(futures, processingErrorsQueue);
//
//        } catch (IOException ioex) {
//            log.error("I/O error during Excel streaming", ioex);
//            processingErrorsQueue.add("I/O error reading Excel file: " + ioex.getMessage());
//            throw ioex;
//        } catch (Exception e) { // Catch broader exceptions from POI/SAX setup or parsing
//            log.error("Error processing Excel file via streaming", e);
//            processingErrorsQueue.add("Excel processing failed: " + e.getMessage());
//            throw new IOException("Failed to process Excel file: " + e.getMessage(), e);
//        }
//
//        return new ParseResult(
//                new ArrayList<>(validCustomersQueue),
//                new ArrayList<>(processingErrorsQueue),
//                processedRowCount.get(),
//                failedRowCount.get()
//        );
//    }
//
//    public void waitForFutures(List<Future<?>> futures, Queue<String> processingErrors) {
//        for (Future<?> future : futures) {
//            try {
//                future.get(FUTURE_GET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//                log.error("Excel row processing thread interrupted", e);
//                processingErrors.add("Processing was interrupted.");
//            } catch (ExecutionException e) {
//                log.error("Error executing Excel row processing task", e.getCause());
//                processingErrors.add("Error during row processing: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
//            } catch (TimeoutException e) {
//                log.error("Timeout waiting for Excel row processing task to complete", e);
//                processingErrors.add("Timeout during row processing. Task cancelled.");
//                future.cancel(true);
//            }
//        }
//        futures.clear();
//    }
//
//    // --- Nested Class for SAX Processing ---
//    private static class SheetToCustomerProcessor implements XSSFSheetXMLHandler.SheetContentsHandler {
//
//        // Column Index Constants for Readability
//        private static final int COL_INDEX = 0; // Assuming index is column 0
//        private static final int COL_CUSTOMER_ID = 1;
//        private static final int COL_FIRST_NAME = 2;
//        private static final int COL_LAST_NAME = 3;
//        private static final int COL_COMPANY = 4;
//        private static final int COL_CITY = 5;
//        private static final int COL_COUNTRY = 6;
//        private static final int COL_PHONE_1 = 7;
//        private static final int COL_PHONE_2 = 8;
//        private static final int COL_EMAIL = 9;
//        private static final int COL_SUBSCRIPTION_DATE = 10;
//        private static final int COL_WEBSITE = 11;
//
//
//        private final Queue<Customer> validCustomersQueue;
//        private final Queue<String> processingErrorsQueue;
//        private final AtomicInteger processedRowCount;
//        private final AtomicInteger failedRowCount;
//        private final List<Future<?>> futures;
//        private final String createdBy;
//        private final Validator validator;
//        private final ExecutorService virtualThreadExecutor;
//        private final ExcelFileParser_old parentParser;
//
//        private boolean isFirstRow = true; // Skip header row
//        private int currentRowNum = -1;
//        private int currentColNum = -1; // Track column index within the row
//        private final String[] currentRowData = new String[EXPECTED_COLUMN_COUNT];
//
//        SheetToCustomerProcessor(Queue<Customer> validCustomersQueue,
//                                 Queue<String> processingErrorsQueue,
//                                 AtomicInteger processedRowCount,
//                                 AtomicInteger failedRowCount,
//                                 List<Future<?>> futures,
//                                 String createdBy,
//                                 Validator validator,
//                                 ExecutorService virtualThreadExecutor,
//                                 ExcelFileParser_old parentParser) {
//            this.validCustomersQueue = validCustomersQueue;
//            this.processingErrorsQueue = processingErrorsQueue;
//            this.processedRowCount = processedRowCount;
//            this.failedRowCount = failedRowCount;
//            this.futures = futures;
//            this.createdBy = createdBy;
//            this.validator = validator;
//            this.virtualThreadExecutor = virtualThreadExecutor;
//            this.parentParser = parentParser;
//        }
//
//        @Override
//        public void startRow(int rowNum) {
//            this.isFirstRow = (rowNum == 0); // Header is assumed to be row 0
//            if (!this.isFirstRow) {
//                this.currentRowNum = rowNum;
//                this.currentColNum = -1; // Reset column counter for the new row
//                Arrays.fill(this.currentRowData, null);
//            }
//        }
//
//        @Override
//        public void endRow(int rowNum) {
//            if (!isFirstRow && currentRowNum == rowNum) {
//                processedRowCount.incrementAndGet();
//                final int finalRowNum = currentRowNum + 1; // 1-based for processingErrors
//                final String[] rowDataCopy = Arrays.copyOf(currentRowData, currentRowData.length);
//
//                futures.add(virtualThreadExecutor.submit(() -> {
//                    Optional<Customer> customerOpt = parseAndValidateRow(rowDataCopy, finalRowNum, createdBy, processingErrorsQueue, failedRowCount);
//                    customerOpt.ifPresent(validCustomersQueue::add);
//                }));
//
//                if (futures.size() >= PARALLEL_BATCH_SIZE) {
//                    parentParser.waitForFutures(futures, processingErrorsQueue);
//                }
//            }
//            // Reset state variables relevant to row processing
//            this.currentRowNum = -1;
//            this.currentColNum = -1;
//            // No need to fill currentRowData here again, startRow does it.
//        }
//
//        @Override
//        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
//            if (isFirstRow || currentRowNum == -1) {
//                return; // Skip header or if not in a valid data row state
//            }
//
//            // Increment column index based on cell calls within the row
//            currentColNum++;
//
//            // Store value if it's within expected bounds
//            if (currentColNum >= 0 && currentColNum < EXPECTED_COLUMN_COUNT) {
//                currentRowData[currentColNum] = formattedValue;
//            } else {
//                // Optional: Log if more columns are found than expected for this row
//                if (currentColNum == EXPECTED_COLUMN_COUNT) { // Log only once per row
//                    log.warn("Line {}: Found more columns than expected (expected {}). Ignoring extra columns.", (currentRowNum + 1), EXPECTED_COLUMN_COUNT);
//                    // Add to processingErrorsQueue if this should be reported as an error
//                    // processingErrorsQueue.add("Line " + (currentRowNum + 1) + ": Found more columns than expected.");
//                }
//            }
//        }
//
//        @Override public void headerFooter(String text, boolean isHeader, String tagName) {}
//
//        // --- Row Parsing and Validation Logic ---
//
//        private Optional<Customer> parseAndValidateRow(String[] rowData, int lineNumber, String createdBy, Queue<String> processingErrors, AtomicInteger failedRowCount) {
//            try {
//                Customer customer = new Customer();
//                // Use constants for column indices
//                customer.setCustomerId(getTrimmedString(rowData, COL_CUSTOMER_ID));
//                customer.setFirstName(getTrimmedString(rowData, COL_FIRST_NAME));
//                customer.setLastName(getTrimmedString(rowData, COL_LAST_NAME));
//                customer.setCompany(getTrimmedString(rowData, COL_COMPANY));
//                customer.setCity(getTrimmedString(rowData, COL_CITY));
//                customer.setCountry(getTrimmedString(rowData, COL_COUNTRY));
//                customer.setPhone1(getTrimmedString(rowData, COL_PHONE_1));
//                customer.setPhone2(getTrimmedString(rowData, COL_PHONE_2));
//                customer.setEmail(getTrimmedString(rowData, COL_EMAIL));
//                customer.setSubscriptionDate(parseDate(getTrimmedString(rowData, COL_SUBSCRIPTION_DATE), lineNumber, processingErrors));
//                customer.setWebsite(getTrimmedString(rowData, COL_WEBSITE));
//
//                customer.setCreatedBy(createdBy);
//                customer.setUpdatedBy(createdBy);
//
//                return validateCustomer(customer, lineNumber, processingErrors, failedRowCount);
//
//            } catch (Exception e) {
//                processingErrors.add("Line " + lineNumber + ": Unexpected error parsing streamed row: " + e.getMessage());
//                failedRowCount.incrementAndGet();
//                log.error("Unexpected error parsing streamed Excel row {}", lineNumber, e);
//                return Optional.empty();
//            }
//        }
//
//        private Optional<Customer> validateCustomer(Customer customer, int lineNumber, Queue<String> processingErrors, AtomicInteger failedRowCount) {
//            Set<ConstraintViolation<Customer>> violations = validator.validate(customer);
//            if (violations.isEmpty()) {
//                return Optional.of(customer);
//            } else {
//                violations.forEach(violation ->
//                        processingErrors.add("Line " + lineNumber + ": Validation failed - Field '" + violation.getPropertyPath() + "': " + violation.getMessage())
//                );
//                failedRowCount.incrementAndGet();
//                return Optional.empty();
//            }
//        }
//
//        private String getTrimmedString(String[] rowData, int index) {
//            if (index >= 0 && index < rowData.length && rowData[index] != null) {
//                return rowData[index].trim();
//            }
//            return null;
//        }
//
//        /**
//         * Parses a date string, trying primary and fallback formats.
//         */
//        private LocalDate parseDate(String dateString, int lineNumber, Queue<String> processingErrors) {
//            if (dateString == null || dateString.isBlank()) {
//                return null;
//            }
//            String trimmedDate = dateString.trim();
//            try {
//                // Try primary format (e.g., yyyy-MM-dd)
//                return LocalDate.parse(trimmedDate, PRIMARY_DATE_FORMATTER);
//            } catch (DateTimeParseException e1) {
//                try {
//                    // Try fallback format (e.g., MM/dd/yyyy)
//                    return LocalDate.parse(trimmedDate, FALLBACK_DATE_FORMATTER_MDY);
//                } catch (DateTimeParseException e2) {
//                    // Could add more formats or attempt numeric parsing if needed
//                    log.warn("Line {}: Could not parse date '{}' using known formats.", lineNumber, trimmedDate);
//                    processingErrors.add("Line " + lineNumber + ": Invalid date format for '" + trimmedDate + "'. Tried formats: yyyy-MM-dd, MM/dd/yyyy");
//                    return null;
//                }
//            }
//        }
//    } // End of SheetToCustomerProcessor
//}