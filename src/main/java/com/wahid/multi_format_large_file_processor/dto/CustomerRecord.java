package com.wahid.multi_format_large_file_processor.dto; // Or a suitable package

import jakarta.validation.constraints.*; // Assuming you want validation here too
import java.time.LocalDate;

/**
 * Represents customer data parsed from an input file.
 * Using a Record for immutability and conciseness.
 */
public record CustomerRecord(
    // Consider adding validation annotations if validation should happen on the Record
    // Note: Validation on Records might require specific handling or libraries depending on the framework version.
    // Alternatively, keep validation on the mutable entity if mapping later.

    String customerId, // Assuming this maps to COL_CUSTOMER_ID etc.
    String firstName,
    String lastName,
    String company,
    String city,
    String country,
    String phone1,
    String phone2,
    String email,
    LocalDate subscriptionDate,
    String website,
    String createdBy // Include fields needed downstream
) {
    // You can add compact constructors for validation or normalization if needed
    // public CustomerRecord {
    //     // e.g., Objects.requireNonNull(customerId, "Customer ID cannot be null");
    // }
}