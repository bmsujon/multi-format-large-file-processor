package com.wahid.multi_format_large_file_processor.processor;

import com.wahid.multi_format_large_file_processor.entity.Customer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


@Component
@Scope(value = "step", proxyMode = ScopedProxyMode.TARGET_CLASS) // Use TARGET_CLASS for concrete class proxy
public class CustomerItemProcessor implements ItemProcessor<Customer, Customer> { // Input type matches Reader Output

    private static final Logger log = LoggerFactory.getLogger(CustomerItemProcessor.class);

    @Autowired
    private Validator validator;

    @Value("#{jobParameters['createdBy']}")
    private String createdBy;

    // Optional: Track line number, use AtomicInteger for thread safety if parallel steps/chunks are used
    private final AtomicInteger currentLineNumber = new AtomicInteger(0);

    // Optional: Collect validation processingErrors if needed for summary reporting (using a listener is often better)
    private final Set<String> validationErrors = ConcurrentHashMap.newKeySet();


    @Override
    public Customer process(Customer customer) throws Exception {
        int line = currentLineNumber.incrementAndGet() + 1; // +1 because reader skips header

        // 1. Validation
        Set<ConstraintViolation<Customer>> violations = validator.validate(customer);
        if (!violations.isEmpty()) {
            String errorMsg = String.format("Validation failed at approx line %d for CustomerId '%s': %s",
                    line, customer.getCustomerId(), formatViolations(violations));
            log.warn(errorMsg);
            validationErrors.add(errorMsg); // Collect error message (consider logging listener instead)
            return null; // Skip item
        }

        // 2. Set Audit Fields
        customer.setCreatedBy(createdBy);
        customer.setUpdatedBy(createdBy); // Initially same

        // 3. Optional Transformations (if any needed beyond reader mapping)

        log.trace("Processed valid customer at approx line {}: {}", line, customer.getCustomerId());
        return customer; // Pass valid customer to writer
    }

    private String formatViolations(Set<ConstraintViolation<Customer>> violations) {
        return violations.stream()
                .map(v -> String.format("[%s: %s]", v.getPropertyPath(), v.getMessage()))
                .collect(Collectors.joining(", "));
    }

    // Optional: Implement StepExecutionListener methods (@BeforeStep, @AfterStep)
    // here or in a separate listener bean to initialize/log summary data.
    // For example, log the count of validationErrors in @AfterStep.
}