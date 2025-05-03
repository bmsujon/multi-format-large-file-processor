package com.wahid.multi_format_large_file_processor.service;

import com.wahid.multi_format_large_file_processor.dto.CustomerRecord;
import com.wahid.multi_format_large_file_processor.dto.ParseResult;
import com.wahid.multi_format_large_file_processor.dto.ProcessingSummary;
import com.wahid.multi_format_large_file_processor.entity.Customer;
import com.wahid.multi_format_large_file_processor.repository.CustomerRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors; // Add this import

@Service
public class FileProcessingService {

    private static final Logger log = LoggerFactory.getLogger(FileProcessingService.class);

    private final Map<String, FileParser> fileParsers;
    private final CustomerRepository customerRepository;
    private final Validator validator;

    @Value("${app.batch.size:100}") // Default batch size 100 if not specified in properties
    private int batchSize;

    // Helper record for stream processing
    private record ValidationResult(CustomerRecord record, Customer customer, Set<ConstraintViolation<Customer>> violations) {
        boolean isValid() {
            return violations == null || violations.isEmpty();
        }
    }


    @Autowired
    public FileProcessingService(List<FileParser> parsers, // Inject list of all parsers
                                 CustomerRepository customerRepository,
                                 Validator validator) {
        // Create a map of parsers keyed by their supported file type
        this.fileParsers = parsers.stream()
                .collect(Collectors.toMap(FileParser::getSupportedFileType, parser -> parser));
        this.customerRepository = customerRepository;
        this.validator = validator;
        log.info("Initialized FileProcessingService with parsers for: {}", this.fileParsers.keySet());
    }

    @Transactional // Ensure atomicity for the entire file processing if needed, or apply to batch saving
    public ProcessingSummary processFile(InputStream inputStream, String fileType, String originalFilename, String createdBy) throws IOException {
        FileParser parser = fileParsers.get(fileType.toLowerCase());
        if (parser == null) {
            log.error("Unsupported file type: {}", fileType);
            throw new IllegalArgumentException("Unsupported file type: " + fileType);
        }

        log.info("Processing file '{}' of type '{}' using parser: {}", originalFilename, fileType, parser.getClass().getSimpleName());
        long startTime = System.currentTimeMillis();

        ParseResult parseResult = parser.parse(inputStream, createdBy);

        List<String> allProcessingErrors = new ArrayList<>(parseResult.processingErrors());
        AtomicInteger totalSaved = new AtomicInteger(0);
        // Initialize totalFailed with failures reported by the parser
        AtomicInteger totalFailed = new AtomicInteger(parseResult.failedRowCount());

        List<CustomerRecord> validRecords = parseResult.validRecords();
        log.info("Parser finished. Received {} potential records. Initial failures: {}. Starting validation and saving.",
                validRecords.size(), totalFailed.get());


        // --- Stream Processing Logic ---
        AtomicInteger validationFailures = new AtomicInteger(0); // Count failures during this stage

        List<Customer> customersToSave = validRecords.stream()
                // 1. Map Record to Entity and Validate -> ValidationResult
                .map(record -> {
                    Customer customer = mapRecordToEntity(record);
                    Set<ConstraintViolation<Customer>> violations = validator.validate(customer);
                    return new ValidationResult(record, customer, violations);
                })
                // 2. Filter out invalid records, collecting processingErrors
                .filter(result -> {
                    if (result.isValid()) {
                        return true; // Keep valid records
                    } else {
                        // Collect validation processingErrors for invalid records
                        result.violations().forEach(v ->
                                allProcessingErrors.add("Record [" + result.record().customerId() + "]: " + v.getPropertyPath() + " " + v.getMessage())
                        );
                        validationFailures.incrementAndGet(); // Count this record as failed
                        return false; // Discard invalid records from this stream
                    }
                })
                // 3. Extract the valid Customer entity
                .map(ValidationResult::customer)
                // 4. Collect valid customers into a list
                .toList(); // Java 21 .toList() - returns unmodifiable list

        totalFailed.addAndGet(validationFailures.get()); // Add validation failures to total count
        log.info("Validation complete. {} records passed validation, {} failed.", customersToSave.size(), validationFailures.get());

        // --- Batch Saving Logic ---
        if (!customersToSave.isEmpty()) {
            log.info("Starting batch save for {} valid customers with batch size {}.", customersToSave.size(), batchSize);
            // Save valid customers in batches
            for (int i = 0; i < customersToSave.size(); i += batchSize) {
                int end = Math.min(i + batchSize, customersToSave.size());
                List<Customer> batch = customersToSave.subList(i, end); // subList is efficient
                try {
                    customerRepository.saveAll(batch);
                    customerRepository.flush(); // Ensure changes are sent to DB within the batch
                    totalSaved.addAndGet(batch.size());
                    log.debug("Saved batch {}/{} ({} records)", (i / batchSize) + 1, (customersToSave.size() + batchSize - 1) / batchSize, batch.size());
                } catch (Exception e) {
                    // Handle batch save exception (e.g., database constraint)
                    log.error("Error saving batch starting at index {}: {}", i, e.getMessage(), e);
                    // Add a general error for the failed batch
                    int failedInBatch = batch.size();
                    allProcessingErrors.add("Failed to save batch of " + failedInBatch + " records starting near record " + (i + 1) + ". Error: " + e.getMessage());
                    totalFailed.addAndGet(failedInBatch); // Mark records in this batch as failed
                    totalSaved.addAndGet(-failedInBatch); // Adjust saved count if necessary (or track separately)
                    // Decide whether to continue with next batch or abort
                    // For now, we continue, but you might want to throw an exception here
                }
            }
        } else {
            log.info("No valid customers to save.");
        }

        long endTime = System.currentTimeMillis();
        log.info("File processing finished for '{}'. Time: {} ms. Total Processed (by parser): {}, Saved: {}, Failed: {}",
                originalFilename, (endTime - startTime), parseResult.processedRowCount(), totalSaved.get(), totalFailed.get());

        return new ProcessingSummary(
                parseResult.processedRowCount(), // Rows attempted by parser (excluding header)
                totalSaved.get(),
                totalFailed.get(),
                allProcessingErrors,
                (endTime - startTime)
        );
    }

    /**
     * Maps a CustomerRecord DTO to a Customer entity.
     *
     * @param record The CustomerRecord DTO.
     * @return The corresponding Customer entity.
     */
    private Customer mapRecordToEntity(CustomerRecord record) {
        Customer customer = new Customer();
        // Assuming Customer entity has appropriate setters
        customer.setCustomerId(record.customerId());
        customer.setFirstName(record.firstName());
        customer.setLastName(record.lastName());
        customer.setCompany(record.company());
        customer.setCity(record.city());
        customer.setCountry(record.country());
        customer.setPhone1(record.phone1());
        customer.setPhone2(record.phone2());
        customer.setEmail(record.email());
        customer.setSubscriptionDate(record.subscriptionDate());
        customer.setWebsite(record.website());
        // Use Instant.now() to match the expected type for setCreatedAt
        customer.setCreatedAt(java.time.Instant.now()); // Set creation timestamp
        customer.setCreatedBy(record.createdBy()); // Set creator info
        return customer;
    }
}