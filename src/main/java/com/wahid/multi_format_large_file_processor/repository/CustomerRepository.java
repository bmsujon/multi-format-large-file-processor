package com.wahid.multi_format_large_file_processor.repository;

import com.wahid.multi_format_large_file_processor.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByCustomerId(String customerId);
    Optional<Customer> findByEmail(String email);
}
