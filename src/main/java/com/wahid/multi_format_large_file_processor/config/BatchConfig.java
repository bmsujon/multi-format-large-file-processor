package com.wahid.multi_format_large_file_processor.config;

import com.wahid.multi_format_large_file_processor.entity.Customer;
import com.wahid.multi_format_large_file_processor.exception.BatchValidationException;
import com.wahid.multi_format_large_file_processor.service.ExcelStreamingItemReader; // Ensure correct import path
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
import org.springframework.batch.item.ItemStreamReader; // Keep this import
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
import org.springframework.context.annotation.ScopedProxyMode; // Import if needed
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.validation.BindException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;


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

    // --- Reader Beans (CSV, Excel, Composite) ---

    @Bean
    @Scope(value = "step", proxyMode = ScopedProxyMode.TARGET_CLASS)
    public FlatFileItemReader<Customer> csvCustomerReader(
            @Value("#{jobParameters['inputFile']}") String inputFile) {
        log.debug("Instantiating CSV Reader bean definition for file: {}", inputFile);
        return new FlatFileItemReaderBuilder<Customer>()
                .name("csvCustomerReader")
                .resource(new FileSystemResource(inputFile))
                .linesToSkip(1)
                .delimited()
                .names(csvColumns.split(","))
                .fieldSetMapper(new CustomCustomerFieldSetMapper())
                .build();
    }

    @Bean
    @Scope(value = "step", proxyMode = ScopedProxyMode.TARGET_CLASS)
    public ExcelStreamingItemReader excelCustomerReader(
            @Value("#{jobParameters['inputFile']}") String inputFile) {
        log.debug("Instantiating ExcelStreamingItemReader bean definition for file: {}", inputFile);
        ExcelStreamingItemReader reader = new ExcelStreamingItemReader();
        reader.setResource(new FileSystemResource(inputFile));
        // afterPropertiesSet will be called by Spring automatically
        return reader;
    }

    @Bean
    // ADD @Scope("step", ...) BACK HERE
    @Scope(value = "step", proxyMode = ScopedProxyMode.TARGET_CLASS)
    public ItemReader<Customer> customerItemReader(
            // This @Value needs the step scope to access jobParameters
            @Value("#{jobParameters['fileType']}") String fileType,
            // Inject the specific reader beans (which are step-scoped proxies)
            FlatFileItemReader<Customer> csvCustomerReader,
            ExcelStreamingItemReader excelCustomerReader
    ) {
        log.info("Selecting reader for file type '{}'", fileType);
        if ("csv".equalsIgnoreCase(fileType)) {
            // Return the step-scoped proxy for the CSV reader
            return csvCustomerReader;
        } else if ("excel".equalsIgnoreCase(fileType)) {
            // Return the step-scoped proxy for the Excel reader
            return excelCustomerReader;
        } else {
            log.error("Unsupported file type for batch reader: {}", fileType);
            throw new IllegalArgumentException("Unsupported file type for batch reader: " + fileType);
        }
    }


    // --- Processor Bean ---
    @Bean
    @Scope(value = "step", proxyMode = ScopedProxyMode.TARGET_CLASS)
    public ItemProcessor<Customer, Customer> customerProcessor(@Value("#{jobParameters['triggeredBy']}") String triggeredBy) {
        return customer -> {
            try {
                customer.setCreatedBy(triggeredBy);
                customer.setUpdatedBy(triggeredBy);

                Set<ConstraintViolation<Customer>> violations = validator.validate(customer);
                if (!violations.isEmpty()) {
                    log.warn("Validation failed for customer ID [{}]: {}", customer.getCustomerId(), violations);
                    throw new BatchValidationException("Validation failed: " + violations);
                }
                return customer;
            } catch (BatchValidationException e) {
                throw e;
            } catch (Exception e) {
                log.error("Unexpected error processing customer ID [{}]. Returning null to skip.", customer.getCustomerId(), e);
                return null;
            }
        };
    }

    // --- Custom FieldSetMapper for CSV Date Conversion ---
    public static class CustomCustomerFieldSetMapper implements FieldSetMapper<Customer> {
        @Override
        public Customer mapFieldSet(FieldSet fieldSet) throws BindException {
            Customer customer = new Customer();
            try {
                customer.setCustomerId(fieldSet.readString("customerId"));
                customer.setFirstName(fieldSet.readString("firstName"));
                customer.setLastName(fieldSet.readString("lastName"));
                customer.setCompany(fieldSet.readString("company"));
                customer.setCity(fieldSet.readString("city"));
                customer.setCountry(fieldSet.readString("country"));
                customer.setPhone1(fieldSet.readString("phone1"));
                customer.setPhone2(fieldSet.readString("phone2"));
                customer.setEmail(fieldSet.readString("email"));
                customer.setWebsite(fieldSet.readString("website"));

                String dateStr = fieldSet.readString("subscriptionDateStr");
                if (dateStr != null && !dateStr.isBlank()) {
                    String cleanDateString = dateStr.trim().replace("\"", "");
                    customer.setSubscriptionDate(parseDate(cleanDateString));
                }
                return customer;
            } catch (DateTimeParseException e) {
                String failedDate = fieldSet.readString("subscriptionDateStr");
                log.warn("CSV Mapper: Could not parse date '{}' for customerId [{}].", failedDate, fieldSet.readString("customerId"), e);
                throw new FlatFileParseException("Invalid date format: " + failedDate, fieldSet.getValues().toString(), fieldSet.getNames().length);
            } catch (Exception e) {
                log.error("CSV Mapper: Error mapping FieldSet to Customer: {}", fieldSet.getProperties(), e);
                throw new BindException(fieldSet, "customer");
            }
        }

        private LocalDate parseDate(String cleanDateString) throws DateTimeParseException {
            try {
                return LocalDate.parse(cleanDateString, PRIMARY_DATE_FORMATTER);
            } catch (DateTimeParseException e1) {
                return LocalDate.parse(cleanDateString, FALLBACK_DATE_FORMATTER_MDY);
            }
        }
    }


    // --- Writer Bean ---
    @Bean
    public JpaItemWriter<Customer> customerJpaWriter(EntityManagerFactory entityManagerFactory) {
        log.debug("Configuring JpaItemWriter.");
        return new JpaItemWriterBuilder<Customer>()
                .entityManagerFactory(entityManagerFactory)
                .build();
    }

    // --- Step Definitions ---

    @Bean
    public Step customerProcessingStep(JobRepository jobRepository,
                                       PlatformTransactionManager transactionManager,
                                       // Inject the specific CSV reader directly for testing
                                       FlatFileItemReader<Customer> csvCustomerReader,
                                       // @Qualifier("customerItemReader") ItemReader<Customer> customerItemReader, // Keep this commented for now if the direct test worked
                                       ItemProcessor<Customer, Customer> customerProcessor,
                                       JpaItemWriter<Customer> customerJpaWriter) {
        // Add a note to the log for clarity during testing
        log.debug("Configuring customerProcessingStep [CSV ONLY TEST] with chunk size {} and skip limit {}.", chunkSize, skipLimit);

        return new StepBuilder("customerProcessingStep", jobRepository)
                .<Customer, Customer>chunk(chunkSize, transactionManager)
                // Use the injected csvCustomerReader directly
                .reader(csvCustomerReader)
                .processor(customerProcessor)
                .writer(customerJpaWriter)
                .faultTolerant()
                .skipLimit(skipLimit)
                // Exceptions during read/process
                .skip(DateTimeParseException.class)
                .skip(FlatFileParseException.class)
                .skip(ParseException.class)
                .skip(IllegalArgumentException.class)
                .skip(BindException.class)
                .skip(BatchValidationException.class)
                // --- ADD THIS LINE for write processingErrors ---
                .skip(org.springframework.dao.DataIntegrityViolationException.class) // Skip DB constraint processingErrors on write
                // --- Optional: Also skip the Hibernate specific one for robustness ---
                .skip(org.hibernate.exception.ConstraintViolationException.class)

                // Configure noRollback for skipped exceptions if desired (keeps transaction for valid items)
                .noRollback(DateTimeParseException.class)
                .noRollback(FlatFileParseException.class)
                .noRollback(ParseException.class)
                .noRollback(IllegalArgumentException.class)
                .noRollback(BindException.class)
                .noRollback(BatchValidationException.class)
                // --- ADD THIS LINE for write processingErrors ---
                .noRollback(org.springframework.dao.DataIntegrityViolationException.class)
                // --- Optional: Also skip the Hibernate specific one for robustness ---
                .noRollback(org.hibernate.exception.ConstraintViolationException.class)
                .build();
    }

    @Bean
    public Step cleanupStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        log.debug("Configuring cleanupStep.");
        Tasklet cleanupTasklet = (contribution, chunkContext) -> {
            String inputFile = chunkContext.getStepContext().getJobParameters().get("inputFile").toString();
            log.info("Attempting to delete temporary file: {}", inputFile);
            try {
                boolean deleted = Files.deleteIfExists(Paths.get(inputFile));
                if (deleted) {
                    log.info("Successfully deleted temporary file: {}", inputFile);
                } else {
                    log.warn("Temporary file not found for deletion: {}", inputFile);
                }
            } catch (IOException e) {
                log.error("Failed to delete temporary file: {}", inputFile, e);
            }
            return RepeatStatus.FINISHED;
        };

        return new StepBuilder("cleanupStep", jobRepository)
                .tasklet(cleanupTasklet, transactionManager)
                .build();
    }


    // --- Job Definition ---
    @Bean(name = "customerProcessingJob")
    public Job customerProcessingJob(JobRepository jobRepository, Step customerProcessingStep, Step cleanupStep) {
        log.debug("Configuring customerProcessingJob.");
        return new JobBuilder("customerProcessingJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(customerProcessingStep)
                .next(cleanupStep)
                .build();
    }

    // --- Asynchronous Job Launcher Configuration ---

    @Bean
    public TaskExecutor batchTaskExecutor() {
        SimpleAsyncTaskExecutor asyncTaskExecutor = new SimpleAsyncTaskExecutor("batch-");
        log.debug("Configuring SimpleAsyncTaskExecutor for batch jobs.");
        return asyncTaskExecutor;
    }

    @Bean(name = "asyncJobLauncher")
    public JobLauncher asyncJobLauncher(JobRepository jobRepository, TaskExecutor batchTaskExecutor) throws Exception {
        TaskExecutorJobLauncher jobLauncher = new TaskExecutorJobLauncher();
        jobLauncher.setJobRepository(jobRepository);
        jobLauncher.setTaskExecutor(batchTaskExecutor);
        jobLauncher.afterPropertiesSet();
        log.debug("Configuring TaskExecutorJobLauncher (async).");
        return jobLauncher;
    }
}