package com.wahid.multi_format_large_file_processor.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "customers")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", unique = true)
    @NotBlank(message = "Customer ID cannot be blank")
    @Size(max = 255)
    private String customerId;

    @Column(name = "first_name")
    @Size(max = 255)
    private String firstName;

    @Column(name = "last_name")
    @Size(max = 255)
    private String lastName;

    @Column(name = "company")
    @Size(max = 255)
    private String company;

    @Column(name = "city")
    @Size(max = 255)
    private String city;

    @Column(name = "country")
    @Size(max = 255)
    private String country;

    @Column(name = "phone_1")
    @Size(max = 50)
    private String phone1;

    @Column(name = "phone_2")
    @Size(max = 50)
    private String phone2;

    @Column(name = "email", unique = true)
    @Email(message = "Invalid email format")
    @Size(max = 255)
    private String email;

    @Column(name = "subscription_date")
    private LocalDate subscriptionDate;

    @Column(name = "website")
    @Size(max = 255)
    private String website;

    // Audit fields
    @Column(name = "created_by", updatable = false)
    @Size(max = 255)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_by")
    @Size(max = 255)
    private String updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
