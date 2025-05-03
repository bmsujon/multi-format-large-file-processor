# multi-format-large-file-processor
# Multi-Format Large File Processor

## Overview

This project is a Spring Boot application designed to efficiently process large files in various formats (CSV, JSON, XML). It provides a RESTful API to initiate file processing tasks, which are executed asynchronously in the background. The application leverages modern Java features like Virtual Threads (available from Java 21) for improved concurrency and performance when handling I/O-intensive operations typical of file processing. It also tracks the status of each processing job.

## Features

*   **Asynchronous Processing:** Uploaded or specified files are processed in the background without blocking the main application thread, allowing the API to remain responsive.
*   **Multi-Format Support:** Handles processing logic for different common file formats like CSV, JSON, and XML.
*   **Status Tracking:** Monitors the progress of each file processing task (e.g., `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`).
*   **REST API:** Exposes endpoints to trigger processing and check the status of jobs.
*   **Efficient Concurrency:** Utilizes Java 21 Virtual Threads via a configured `TaskExecutor` for potentially better resource utilization compared to traditional platform threads, especially for tasks involving waiting (like I/O).
*   **Persistence:** Stores file processing status and metadata in a database using Spring Data JPA.

## Technology Stack

*   **Java:** Version 21+ (required for Virtual Threads)
*   **Spring Boot:** Core application framework
*   **Spring Web:** For building RESTful APIs
*   **Spring Data JPA:** For database interaction
*   **Spring Async:** For asynchronous method execution support
*   **Project Lombok:** (Likely) To reduce boilerplate code.
*   **Database:** (Configurable in `application.properties` - e.g., H2, PostgreSQL, MySQL)
*   **Build Tool:** Maven or Gradle (check `pom.xml` or `build.gradle`)

## How to Run

1.  **Prerequisites:**
    *   JDK 21 or later installed.
    *   Maven or Gradle installed (depending on the project's build tool).
    *   Access to the database configured in `application.properties` (or ensure an in-memory database like H2 is configured if used for development).

2.  **Clone the repository:**

## API Endpoints

### 1. Initiate File Processing

*   **Endpoint:** `POST /process`
*   **Request Body:** (Likely expects information about the file to process, e.g., path or potentially multipart file upload)

    *Example: If processing based on a file path*
    