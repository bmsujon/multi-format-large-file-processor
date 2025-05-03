package com.wahid.multi_format_large_file_processor.config;

import com.wahid.multi_format_large_file_processor.entity.Customer;
import com.wahid.multi_format_large_file_processor.exception.BatchValidationException;
import com.wahid.multi_format_large_file_processor.service.ExcelStreamingItemReader; // Ensure correct import path
import com.wahid.multi_format_large_file_processor.service.FileTypeDelegatingItemReader; // Import the new class
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
// ItemStreamReader is implicitly handled by ItemStream registration
import org.springframework.batch.item.ParseException;
import org.springframework.batch.item.database.JpaItemWriter;
import org.springframework.batch.item.database.builder.JpaItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.FieldSetMapper;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.task.support.TaskExecutorAdapter; // Import TaskExecutorAdapter
import org.springframework.dao.DataIntegrityViolationException; // Import for skip/noRollback
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.validation.BindException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.concurrent.ExecutorService; // Import ExecutorService

/**
 * Spring Batch configuration for processing customer files (CSV/Excel).
 * Includes reading, validation, processing, writing, and cleanup steps.
 * Uses a delegating reader to handle CSV/Excel dynamically based on job parameters.
 */
@Configuration
@Slf4j
public class BatchConfig {

    // --- Configuration Properties ---
    @Value("${batch.job.customer.chunk-size:100}")
    private int chunkSize;

    @Value("${batch.job.customer.csv.columns}")
    private String csvColumns;

    @Value("${batch.job.customer.skip-limit:100}")
    private int skipLimit;

    // --- Date Formatters ---
    private static final DateTimeFormatter PRIMARY_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter FALLBACK_DATE_FORMATTER_MDY = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    @Autowired
    private Validator validator;

    // --- Reader Beans (CSV, Excel) - Now Singleton Scoped ---

    /**
     * Singleton-scoped reader for CSV customer files.
     * Configured WITHOUT the resource initially. Resource set by delegating reader.
     */
    @Bean // No Step Scope needed here
    public FlatFileItemReader<Customer> csvCustomerReader() {
        log.debug("Instantiating CSV Reader bean definition (singleton).");
        // Resource will be set by the delegating reader
        return new FlatFileItemReaderBuilder<Customer>()
                .name("csvCustomerReader")
                // .resource() // REMOVED - Will be set later by delegate
                .linesToSkip(1) // Skip header row
                .delimited()
                .names(csvColumns.split(","))
                .fieldSetMapper(new CustomCustomerFieldSetMapper())
                .saveState(false) // Recommended for singleton readers used in steps
                .build();
    }

    /**
     * Singleton-scoped reader for Excel customer files.
     * Configured WITHOUT the resource initially. Resource set by delegating reader.
     * IMPORTANT: Ensure ExcelStreamingItemReader implements ItemStream for proper lifecycle.
     */
    @Bean // No Step Scope needed here
    public ExcelStreamingItemReader excelCustomerReader() {
        log.debug("Instantiating ExcelStreamingItemReader bean definition (singleton).");
        ExcelStreamingItemReader reader = new ExcelStreamingItemReader();
        // reader.setResource(); // REMOVED - Will be set later by delegate
        // reader.setSaveState(false); // Add if ExcelStreamingItemReader supports it
        // Ensure ExcelStreamingItemReader implements ItemStream if needed
        return reader;
    }

    /**
     * Step-scoped delegating reader that selects and manages the CSV or Excel reader.
     * Receives job parameters via setters.
     */
    @Bean
    @Scope(value = "step", proxyMode = ScopedProxyMode.TARGET_CLASS)
    public FileTypeDelegatingItemReader customerItemReader(
            @Value("#{jobParameters['fileType']}") String fileType,
            @Value("#{jobParameters['inputFile']}") String inputFile
            // Spring will @Autowired the csvCustomerReader and excelCustomerReader beans into the instance
    ) {
        log.info("Creating step-scoped FileTypeDelegatingItemReader bean for fileType '{}'.", fileType);
        FileTypeDelegatingItemReader delegatingReader = new FileTypeDelegatingItemReader();
        // Set parameters needed by the delegating reader for its open() method
        delegatingReader.setFileType(fileType);
        delegatingReader.setInputFile(inputFile);
        return delegatingReader;
    }


    // --- Processor Bean ---

    /**
     * Step-scoped processor to validate Customer records and set audit fields.
     * Uses the 'triggeredBy' job parameter for audit information.
     */
    @Bean
    @Scope(value = "step", proxyMode = ScopedProxyMode.TARGET_CLASS)
    public ItemProcessor<Customer, Customer> customerProcessor(@Value("#{jobParameters['triggeredBy']}") String triggeredBy) {
        return customer -> {
            try {
                // Set audit fields
                customer.setCreatedBy(triggeredBy);
                customer.setUpdatedBy(triggeredBy);
                // Note: createdAt is likely set during entity mapping or persistence

                // Validate the customer object
                Set<ConstraintViolation<Customer>> violations = validator.validate(customer);
                if (!violations.isEmpty()) {
                    // Log validation failures clearly
                    StringBuilder violationMessages = new StringBuilder();
                    violations.forEach(v -> violationMessages.append(v.getPropertyPath()).append(": ").append(v.getMessage()).append("; "));
                    log.warn("Validation failed for customer ID [{}]: {}", customer.getCustomerId(), violationMessages);
                    // Throw specific exception for skipping
                    throw new BatchValidationException("Validation failed: " + violationMessages);
                }
                // Return valid customer for writing
                return customer;
            } catch (BatchValidationException e) {
                // Re-throw validation exceptions to be handled by skip logic
                throw e;
            } catch (Exception e) {
                // Catch unexpected errors during processing
                log.error("Unexpected error processing customer ID [{}]. Returning null to skip.", customer.getCustomerId(), e);
                // Returning null filters the item from the writer
                return null;
            }
        };
    }

    // --- Custom FieldSetMapper for CSV Date Conversion ---

    /**
     * Maps a FieldSet (from a CSV row) to a Customer entity.
     * Includes robust date parsing logic.
     */
    public static class CustomCustomerFieldSetMapper implements FieldSetMapper<Customer> {
        @Override
        public Customer mapFieldSet(FieldSet fieldSet) throws BindException {
            Customer customer = new Customer();
            String customerId = null; // Keep track for logging
            try {
                customerId = fieldSet.readString("customerId");
                customer.setCustomerId(customerId);
                customer.setFirstName(fieldSet.readString("firstName"));
                customer.setLastName(fieldSet.readString("lastName"));
                customer.setCompany(fieldSet.readString("company"));
                customer.setCity(fieldSet.readString("city"));
                customer.setCountry(fieldSet.readString("country"));
                customer.setPhone1(fieldSet.readString("phone1"));
                customer.setPhone2(fieldSet.readString("phone2"));
                customer.setEmail(fieldSet.readString("email"));
                customer.setWebsite(fieldSet.readString("website"));

                // Handle potential quotes and whitespace in date string
                String dateStr = fieldSet.readString("subscriptionDateStr");
                if (dateStr != null && !dateStr.isBlank()) {
                    String cleanDateString = dateStr.trim().replace("\"", "");
                    customer.setSubscriptionDate(parseDate(cleanDateString));
                }
                // Set creation timestamp during mapping or persistence
                customer.setCreatedAt(java.time.Instant.now());

                return customer;
            } catch (DateTimeParseException e) {
                String failedDate = fieldSet.readString("subscriptionDateStr");
                log.warn("CSV Mapper: Could not parse date '{}' for customerId [{}].", failedDate, customerId, e);
                // Throw specific exception for skipping
                throw new FlatFileParseException("Invalid date format: " + failedDate, fieldSet.getValues().toString(), fieldSet.getNames().length);
            } catch (Exception e) {
                log.error("CSV Mapper: Error mapping FieldSet for customerId [{}] : {}", customerId, fieldSet.getProperties(), e);
                // Throw BindException for general mapping errors
                throw new BindException(fieldSet, "customer");
            }
        }

        /**
         * Parses a date string using primary (ISO) and fallback (MM/dd/yyyy) formats.
         */
        private LocalDate parseDate(String cleanDateString) throws DateTimeParseException {
            try {
                // Try ISO format first
                return LocalDate.parse(cleanDateString, PRIMARY_DATE_FORMATTER);
            } catch (DateTimeParseException e1) {
                // Fallback to MM/dd/yyyy format
                return LocalDate.parse(cleanDateString, FALLBACK_DATE_FORMATTER_MDY);
            }
        }
    }


    // --- Writer Bean ---

    /**
     * Configures a JpaItemWriter to persist Customer entities.
     */
    @Bean
    public JpaItemWriter<Customer> customerJpaWriter(EntityManagerFactory entityManagerFactory) {
        log.debug("Configuring JpaItemWriter.");
        return new JpaItemWriterBuilder<Customer>()
                .entityManagerFactory(entityManagerFactory)
                // .setUsePersist(true) // Use persist instead of merge if entities are always new
                .build();
    }

    // --- Step Definitions ---

    /**
     * Defines the main processing step: read, process (validate), write customers.
     * Configured with chunk processing and fault tolerance (skip logic).
     */
    @Bean
    public Step customerProcessingStep(JobRepository jobRepository,
                                       PlatformTransactionManager transactionManager,
                                       // Inject the STEP-SCOPED delegating reader
                                       @Qualifier("customerItemReader") FileTypeDelegatingItemReader customerItemReader, // Use the delegating type
                                       ItemProcessor<Customer, Customer> customerProcessor,
                                       JpaItemWriter<Customer> customerJpaWriter) {
        log.info("Configuring customerProcessingStep with chunk size {} and skip limit {}.", chunkSize, skipLimit);

        return new StepBuilder("customerProcessingStep", jobRepository)
                .<Customer, Customer>chunk(chunkSize, transactionManager)
                // Use the delegating reader
                .reader(customerItemReader)
                // IMPORTANT: Register the delegating reader as a stream so open/close are called
                .stream(customerItemReader)
                .processor(customerProcessor)
                .writer(customerJpaWriter)
                .faultTolerant()
                .skipLimit(skipLimit)
                // Exceptions during read/process
                .skip(DateTimeParseException.class)
                .skip(FlatFileParseException.class)
                .skip(ParseException.class)
                .skip(IllegalArgumentException.class) // e.g., from reader selection in delegate
                .skip(BindException.class) // From FieldSetMapper
                .skip(BatchValidationException.class) // From Processor
                // Exceptions during write (database constraints)
                .skip(DataIntegrityViolationException.class)
                .skip(org.hibernate.exception.ConstraintViolationException.class) // Hibernate specific

                // Configure noRollback for skipped exceptions to commit valid items in chunk
                .noRollback(DateTimeParseException.class)
                .noRollback(FlatFileParseException.class)
                .noRollback(ParseException.class)
                .noRollback(IllegalArgumentException.class)
                .noRollback(BindException.class)
                .noRollback(BatchValidationException.class)
                .noRollback(DataIntegrityViolationException.class)
                .noRollback(org.hibernate.exception.ConstraintViolationException.class)
                .build();
    }

    /**
     * Defines a step to clean up the temporary input file after processing.
     */
    @Bean
    public Step cleanupStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        log.debug("Configuring cleanupStep.");
        Tasklet cleanupTasklet = (contribution, chunkContext) -> {
            // Retrieve inputFile path from Job Parameters
            String inputFile = chunkContext.getStepContext().getJobParameters().get("inputFile").toString();
            log.info("Cleanup Step: Attempting to delete temporary file: {}", inputFile);
            try {
                boolean deleted = Files.deleteIfExists(Paths.get(inputFile));
                if (deleted) {
                    log.info("Cleanup Step: Successfully deleted temporary file: {}", inputFile);
                } else {
                    log.warn("Cleanup Step: Temporary file not found for deletion: {}", inputFile);
                }
            } catch (IOException e) {
                // Log error but allow job to complete if cleanup fails
                log.error("Cleanup Step: Failed to delete temporary file: {}", inputFile, e);
            }
            return RepeatStatus.FINISHED;
        };

        return new StepBuilder("cleanupStep", jobRepository)
                .tasklet(cleanupTasklet, transactionManager)
                .build();
    }


    // --- Job Definition ---

    /**
     * Defines the main customer processing job, orchestrating the processing and cleanup steps.
     */
    @Bean(name = "customerProcessingJob")
    public Job customerProcessingJob(JobRepository jobRepository,
                                     @Qualifier("customerProcessingStep") Step customerProcessingStep,
                                     @Qualifier("cleanupStep") Step cleanupStep) {
        log.info("Configuring customerProcessingJob.");
        return new JobBuilder("customerProcessingJob", jobRepository)
                .incrementer(new RunIdIncrementer()) // Allows re-running with same parameters
                .start(customerProcessingStep)      // Start with the main processing
                .next(cleanupStep)                  // Followed by the cleanup step
                .build();
    }

    // --- Asynchronous Job Launcher Configuration ---

    /**
     * Configures the asynchronous JobLauncher using the virtual thread executor
     * defined in AsyncConfig.
     *
     * @param jobRepository         The JobRepository instance.
     * @param virtualThreadExecutor The ExecutorService bean (using virtual threads) from AsyncConfig.
     * @return An asynchronously configured JobLauncher.
     * @throws Exception If configuration fails.
     */
    @Bean(name = "asyncJobLauncher")
    public JobLauncher asyncJobLauncher(
            JobRepository jobRepository,
            @Qualifier("virtualThreadExecutor") ExecutorService virtualThreadExecutor // Inject from AsyncConfig
    ) throws Exception {
        TaskExecutorJobLauncher jobLauncher = new TaskExecutorJobLauncher();
        jobLauncher.setJobRepository(jobRepository);
        // Adapt the ExecutorService to Spring's TaskExecutor interface
        jobLauncher.setTaskExecutor(new TaskExecutorAdapter(virtualThreadExecutor));
        jobLauncher.afterPropertiesSet(); // Validate configuration
        log.info("Configuring TaskExecutorJobLauncher (async) using virtualThreadExecutor.");
        return jobLauncher;
    }

    // Removed the separate batchTaskExecutor bean as we now use the virtualThreadExecutor
}