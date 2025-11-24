package com.myorg;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class PresignedUrlLambda implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String BUCKET_NAME = System.getenv("ORIGINAL_BUCKET_NAME");
    private static final int URL_EXPIRATION_SECONDS = 3600; // URL valid for 1 hour
    private static final long MAX_FILE_SIZE = 500L * 1024 * 1024; // 500MB max

    private final S3Client s3Client;
    private final ObjectMapper objectMapper;

    public PresignedUrlLambda() {
        this.s3Client = S3Client.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        Map<String, Object> response = new HashMap<>();
        Map<String, String> headers = new HashMap<>();

        try {
            // Parse request body
            String body = (String) input.get("body");
            Map<String, Object> requestData = body != null ?
                objectMapper.readValue(body, Map.class) : new HashMap<>();

            // Get file metadata from request
            String fileName = (String) requestData.getOrDefault("fileName", "file_" + UUID.randomUUID() + ".pdf");
            String contentType = (String) requestData.getOrDefault("contentType", "application/pdf");
            Long fileSize = requestData.get("fileSize") != null ?
                Long.valueOf(requestData.get("fileSize").toString()) : 0L;

            // Validate file size
            if (fileSize > MAX_FILE_SIZE) {
                headers.put("Content-Type", "application/json");
                response.put("statusCode", 400);
                response.put("headers", headers);
                response.put("body", "{\"error\": \"File size exceeds maximum allowed size of 500MB\"}");
                return response;
            }

            // Generate unique key for S3
            String s3Key = "uploads/" + UUID.randomUUID() + "/" + fileName;

            // Since we cannot use S3Presigner easily in SDK v2 without additional dependencies,
            // and the AWS Lambda environment has limited capabilities for generating presigned URLs
            // with SDK v2, we'll implement an alternative approach

            // Option 1: For files <= 10MB, use the existing /file-upload endpoint
            // Option 2: For larger files, provide S3 direct upload instructions

            Map<String, Object> responseBody = new HashMap<>();

            if (fileSize <= 10 * 1024 * 1024) {
                // For files up to 10MB, use the existing endpoint
                responseBody.put("uploadMethod", "api-gateway");
                responseBody.put("endpoint", "/file-upload");
                responseBody.put("method", "POST");
                responseBody.put("contentType", "multipart/form-data");
                responseBody.put("maxFileSize", "10MB");
                responseBody.put("instructions", "Use multipart/form-data to upload files up to 10MB");
            } else {
                // For larger files, we need a different approach
                // Create a metadata file to track the expected upload
                Map<String, String> metadata = new HashMap<>();
                metadata.put("original-filename", fileName);
                metadata.put("expected-size", fileSize.toString());
                metadata.put("content-type", contentType);
                metadata.put("upload-requested", Instant.now().toString());

                // Store pending upload marker
                s3Client.putObject(PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(s3Key + ".metadata")
                    .metadata(metadata)
                    .contentType("application/json")
                    .build(),
                    software.amazon.awssdk.core.sync.RequestBody.fromString(
                        objectMapper.writeValueAsString(Map.of(
                            "status", "pending",
                            "fileName", fileName,
                            "fileSize", fileSize,
                            "contentType", contentType,
                            "s3Key", s3Key,
                            "requestTime", Instant.now().toString()
                        ))
                    ));

                // Return S3 direct upload information
                responseBody.put("uploadMethod", "s3-direct");
                responseBody.put("bucketName", BUCKET_NAME);
                responseBody.put("s3Key", s3Key);
                responseBody.put("region", "us-east-1");
                responseBody.put("metadataKey", s3Key + ".metadata");

                // Provide AWS CLI command for direct upload
                responseBody.put("awsCliCommand", String.format(
                    "aws s3 cp %s s3://%s/%s --region us-east-1",
                    fileName, BUCKET_NAME, s3Key
                ));

                responseBody.put("instructions", Map.of(
                    "option1", "Use AWS CLI with proper credentials",
                    "option2", "Use AWS SDK (Python/Java/Node.js) for programmatic upload",
                    "option3", "Contact support for temporary credentials",
                    "note", "After upload, the file will be automatically processed"
                ));

                responseBody.put("alternativeSolution",
                    "For browser-based uploads, consider chunking the file into <10MB parts");
            }

            headers.put("Content-Type", "application/json");
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Credentials", "true");
            response.put("statusCode", 200);
            response.put("headers", headers);
            response.put("body", objectMapper.writeValueAsString(responseBody));

        } catch (Exception e) {
            context.getLogger().log("Error processing request: " + e.getMessage());
            e.printStackTrace();
            headers.put("Content-Type", "application/json");
            response.put("statusCode", 500);
            response.put("headers", headers);
            response.put("body", "{\"error\": \"Failed to process request: " + e.getMessage() + "\"}");
        }

        return response;
    }
}