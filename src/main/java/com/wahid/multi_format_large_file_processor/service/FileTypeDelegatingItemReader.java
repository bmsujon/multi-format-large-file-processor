package com.wahid.multi_format_large_file_processor.service; // Or your preferred package

import com.wahid.multi_format_large_file_processor.entity.Customer;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;

/**
 * An ItemReader and ItemStream that delegates to either a CSV or Excel reader
 * based on the fileType. It manages the lifecycle (open/close) of the delegate.
 * This bean should be step-scoped.
 */
@Slf4j
@Setter // Use Lombok setters for fileType and inputFile injection
public class FileTypeDelegatingItemReader implements ItemReader<Customer>, ItemStream {

    // Inject the fully configured (but resource-less) reader beans
    @Autowired
    private FlatFileItemReader<Customer> csvCustomerReader;

    @Autowired
    private ExcelStreamingItemReader excelCustomerReader; // Assuming this also implements ItemStream

    // These will be injected via @Value in the @Bean definition
    private String fileType;
    private String inputFile;

    private ItemReader<Customer> delegateReader;
    private ItemStream delegateStream;

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        log.info("Delegating Reader: Opening for fileType '{}', inputFile '{}'", fileType, inputFile);
        if (inputFile == null || fileType == null) {
            throw new ItemStreamException("inputFile and fileType must be set for FileTypeDelegatingItemReader");
        }

        FileSystemResource resource = new FileSystemResource(inputFile);

        if ("csv".equalsIgnoreCase(fileType)) {
            // Configure and select the CSV reader
            csvCustomerReader.setResource(resource); // Set the resource just before opening
            delegateReader = csvCustomerReader;
            delegateStream = csvCustomerReader; // FlatFileItemReader is an ItemStream
            log.debug("Delegating to CSV reader.");
        } else if ("excel".equalsIgnoreCase(fileType)) {
            // Configure and select the Excel reader
            excelCustomerReader.setResource(resource); // Assuming Excel reader has setResource
            delegateReader = excelCustomerReader;
            // IMPORTANT: Ensure ExcelStreamingItemReader implements ItemStream
            if (excelCustomerReader instanceof ItemStream) {
                delegateStream = (ItemStream) excelCustomerReader;
                log.debug("Delegating to Excel reader (implements ItemStream).");
            } else {
                log.warn("ExcelStreamingItemReader does not implement ItemStream, lifecycle methods (open/close/update) won't be delegated.");
                // delegateStream remains null, only read will be delegated
                log.debug("Delegating to Excel reader (does NOT implement ItemStream).");
            }
        } else {
            throw new ItemStreamException("Unsupported file type for delegating reader: " + fileType);
        }

        // Open the selected delegate stream
        if (delegateStream != null) {
            log.debug("Calling open() on delegate stream: {}", delegateStream.getClass().getSimpleName());
            delegateStream.open(executionContext);
        } else if (delegateReader == null) {
            throw new ItemStreamException("No delegate reader selected for file type: " + fileType);
        }
    }

    @Override
    public Customer read() throws Exception {
        if (delegateReader == null) {
            // This might happen if open() failed or wasn't called correctly
            log.error("Delegate reader is null during read(). Was open() called successfully?");
            throw new IllegalStateException("Delegate reader is not initialized. Ensure open() was called.");
        }
        // Delegate read operation
        return delegateReader.read();
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // Delegate update operation if the delegate is a stream
        if (delegateStream != null) {
            // Log sparingly for update, can be very frequent
            // log.trace("Calling update() on delegate stream: {}", delegateStream.getClass().getSimpleName());
            delegateStream.update(executionContext);
        }
    }

    @Override
    public void close() throws ItemStreamException {
        log.debug("Delegating Reader: Closing delegate stream.");
        // Delegate close operation if the delegate is a stream
        if (delegateStream != null) {
            log.debug("Calling close() on delegate stream: {}", delegateStream.getClass().getSimpleName());
            delegateStream.close();
        }
        // Clean up references
        delegateReader = null;
        delegateStream = null;
    }
}