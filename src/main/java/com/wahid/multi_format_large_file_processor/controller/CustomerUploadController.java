package com.wahid.multi_format_large_file_processor.controller;

import com.wahid.multi_format_large_file_processor.dto.ProcessingSummary;
import com.wahid.multi_format_large_file_processor.service.FileProcessingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value; // Import Value
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/customers")
public class CustomerUploadController {

    private static final Logger log = LoggerFactory.getLogger(CustomerUploadController.class);

    // Use property for temp path, provide default
    @Value("${batch.job.temp-storage-path:/tmp/batch-uploads/}")
    private String tempStoragePath;

    @Autowired
    private FileProcessingService fileProcessingService;

    // Inject the ASYNCHRONOUS JobLauncher
    @Autowired
    @Qualifier("asyncJobLauncher") // Qualify to get the async launcher bean
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("customerProcessingJob")
    private Job customerProcessingJob;

    /**
     * @deprecated This synchronous endpoint is not recommended for large files or high concurrency.
     *             Use the asynchronous /upload/batch endpoint instead.
     */
    @Deprecated(since = "1.1", forRemoval = true)
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) // Specify consumes
    @Operation(
            summary = "Synchronously Upload Customer Data (Deprecated)",
            description = "Uploads a CSV or Excel file for immediate, synchronous processing. Not recommended for large files.",
            deprecated = true // Mark as deprecated in OpenAPI spec
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Processing successful", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ProcessingSummary.class))),
            @ApiResponse(responseCode = "202", description = "Processing completed with processingErrors", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ProcessingSummary.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request (e.g., empty file, invalid file type)"),
            @ApiResponse(responseCode = "500", description = "Internal Server Error during processing")
    })
    public ResponseEntity<?> uploadCustomers(@RequestParam("file") MultipartFile file,
                                             @RequestParam(defaultValue = "SYSTEM") String createdBy) {
        // ... (logic remains the same as before) ...
        log.warn("Received request for deprecated synchronous /upload endpoint.");
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Please upload a non-empty file.");
        }

        String fileType = getFileExtension(Objects.requireNonNull(file.getOriginalFilename()));
        if (fileType == null) {
            return ResponseEntity.badRequest().body("Invalid file type. Only CSV and Excel files are supported.");
        }

        try {
            ProcessingSummary summary = fileProcessingService.processFile(
                    file.getInputStream(),
                    fileType,
                    file.getOriginalFilename(),
                    createdBy
            );

            if (!summary.processingErrors().isEmpty()) {
                log.info("Synchronous processing completed with processingErrors for file '{}'. Summary: {}", file.getOriginalFilename(), summary);
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(summary);
            } else {
                log.info("Synchronous processing completed successfully for file '{}'. Summary: {}", file.getOriginalFilename(), summary);
                return ResponseEntity.ok(summary);
            }

        } catch (IOException e) {
            log.error("Error processing uploaded file synchronously: {}", file.getOriginalFilename(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to process file: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Unsupported file type provided synchronously: {}", fileType);
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error during synchronous file processing: {}", file.getOriginalFilename(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("An unexpected error occurred: " + e.getMessage());
        }
    }

    @PostMapping(value = "/upload/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) // Specify consumes
    @Operation(
            summary = "Asynchronously Upload Customer Data via Batch Job",
            description = "Uploads a CSV or Excel (.xlsx) file. The file is saved temporarily, and a Spring Batch job is launched asynchronously to process it. Returns immediately with status 202 Accepted if the job launch is successful."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "File accepted and batch processing started."),
            @ApiResponse(responseCode = "400", description = "Bad Request (e.g., empty file, invalid file type)"),
            @ApiResponse(responseCode = "500", description = "Internal Server Error (e.g., failed to save file, failed to launch job)")
    })
    public ResponseEntity<?> uploadCustomersBatch(@RequestParam("file") MultipartFile file,
                                                  @RequestParam(defaultValue = "BATCH_JOB") String triggeredBy) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Please upload a non-empty file.");
        }

        String originalFilename = file.getOriginalFilename();
        String fileExtension = getFileExtension(Objects.requireNonNull(originalFilename));
        if (fileExtension == null) {
            return ResponseEntity.badRequest().body("Invalid or unsupported file type. Only CSV and Excel (.xlsx) files are supported for batch processing.");
        }

        Path tempFilePath = null; // Define outside try for potential cleanup in catch
        try {
            // 1. Save the file temporarily
            Path tempDir = Paths.get(tempStoragePath); // Use configured path
            Files.createDirectories(tempDir);
            String uniqueFilename = UUID.randomUUID() + "_" + originalFilename;
            tempFilePath = tempDir.resolve(uniqueFilename);
            try (var inputStream = file.getInputStream()) {
                Files.copy(inputStream, tempFilePath, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("File saved temporarily for batch processing: {}", tempFilePath);

            // 2. Launch the Spring Batch Job using the async launcher
            JobParameters jobParameters = new JobParametersBuilder()
                    .addString("inputFile", tempFilePath.toString())
                    .addString("fileType", fileExtension)
                    .addString("triggeredBy", triggeredBy)
                    .addLong("timestamp", System.currentTimeMillis()) // Ensures unique job instance
                    .toJobParameters();

            // This now uses the asyncJobLauncher
            jobLauncher.run(customerProcessingJob, jobParameters);

            return ResponseEntity.accepted().body("File upload accepted. Batch processing started for: " + originalFilename);

        } catch (IOException e) {
            log.error("Failed to save file temporarily for batch job: {}", originalFilename, e);
            // Optional: Attempt to clean up partially created file if path exists
            if (tempFilePath != null) {
                try { Files.deleteIfExists(tempFilePath); } catch (IOException ignored) {}
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to store file for batch processing.");
        } catch (Exception e) { // Catch specific Batch exceptions if possible
            log.error("Failed to launch batch job for file: {}", originalFilename, e);
            // Optional: Attempt to clean up file if launch fails after saving
            if (tempFilePath != null) {
                try { Files.deleteIfExists(tempFilePath); } catch (IOException ignored) {}
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to start batch processing job: " + e.getMessage());
        }
    }

    // getFileExtension method remains the same
    private String getFileExtension(String filename) {
        if (filename == null || filename.lastIndexOf('.') == -1) {
            return null;
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (extension) {
            case "xlsx" -> "excel";
            case "csv" -> "csv";
            default -> null;
        };
    }
}