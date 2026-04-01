package com.myorg;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class FileValidationLambda implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
  private final S3Client s3Client;
  private final DynamoDbClient dynamoDbClient;
  private final String originalBucketName;
  private final String jobStatusTable;
  private static final Set<String> ALLOWED_CT = Set.of("application/pdf", "application/epub+zip", "text/plain");
  private static final Set<String> ALLOWED_FILE_EXTENSIONS = Set.of(".pdf", ".epub", ".txt");

  public FileValidationLambda() {
    this(S3Client.builder().region(Region.US_EAST_1).build(),
         DynamoDbClient.builder().region(Region.US_EAST_1).build(),
         System.getenv("ORIGINAL_BUCKET_NAME"),
         System.getenv("JOB_STATUS_TABLE"));
  }

  // Constructor for testing
  public FileValidationLambda(S3Client s3Client, String originalBucketName) {
    this(s3Client, DynamoDbClient.builder().region(Region.US_EAST_1).build(), originalBucketName, null);
  }

  public FileValidationLambda(S3Client s3Client, DynamoDbClient dynamoDbClient, String originalBucketName, String jobStatusTable) {
    this.s3Client = s3Client;
    this.dynamoDbClient = dynamoDbClient;
    this.originalBucketName = originalBucketName;
    this.jobStatusTable = jobStatusTable;
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
    context.getLogger().log("Received event: " + event);
    context.getLogger().log("Original bucket name: " + originalBucketName);

    final Map<String, String> headers = event.getHeaders();
    final String contentType = header(headers, "content-type");
    final boolean isBase64 = Boolean.TRUE.equals((event.getIsBase64Encoded()));
    String body = event.getBody();

    if (body == null) {
      return resp(400, "No body received");
    }

    byte[] fileBytes;
    String fileName;
    String fileContentType;
    
    // Check if this is a multipart/form-data request
    if (contentType != null && contentType.toLowerCase().startsWith("multipart/form-data")) {
      context.getLogger().log("Processing multipart/form-data upload");
      context.getLogger().log("Request is base64 encoded: " + isBase64);
      context.getLogger().log("Body length: " + (body != null ? body.length() : 0));

      try {
        // Parse multipart data
        FileUploadResult result = parseMultipartData(body, contentType, isBase64, context);
        fileBytes = result.content;
        fileName = result.filename;
        fileContentType = result.contentType;
        
        if (fileBytes == null || fileName == null) {
          return resp(400, "No file found in multipart data");
        }
        
        // Validate file type based on extension
        if (!isAllowedFile(fileName, fileContentType)) {
          return resp(415, "Unsupported file type. Allowed: PDF, EPUB, TXT files");
        }
        
        context.getLogger().log("Multipart file: " + fileName + ", type: " + fileContentType + ", size: " + fileBytes.length);
        
      } catch (Exception e) {
        context.getLogger().log("Error parsing multipart data: " + e.getMessage());
        return resp(400, "Failed to parse multipart data: " + e.getMessage());
      }
    } else {
      // Handle direct file upload (non-multipart)
      if (contentType == null || ALLOWED_CT.stream().noneMatch(allowed -> contentType.toLowerCase().startsWith(allowed.toLowerCase()))) {
        return resp(415, "Unsupported media type. Allowed: application/pdf, application/epub+zip, text/plain, or multipart/form-data");
      }
      
      fileBytes = isBase64 ? Base64.getDecoder().decode(body) : body.getBytes(StandardCharsets.ISO_8859_1);
      fileName = extractFileName(header(headers, "content-disposition"), contentType);
      fileContentType = contentType;
    }
    context.getLogger().log("Using filename: " + fileName);

    // Generate a unique job ID for tracking through the pipeline
    String jobId = UUID.randomUUID().toString().substring(0, 8) + "-" + Instant.now().getEpochSecond();

    // Extract user email from Cognito JWT token (API Gateway already validated it)
    String userEmail = extractEmailFromJwt(headers, context);

    // Extract file extension
    String extension = "";
    int dotIdx = fileName.lastIndexOf('.');
    if (dotIdx >= 0) {
      extension = fileName.substring(dotIdx);
    }

    // Use job_id as S3 key prefix to make it traceable
    String s3Key = jobId + extension;

    // Extract optional voice query parameter
    Map<String, String> queryParams = event.getQueryStringParameters();
    String voice = (queryParams != null) ? queryParams.get("voice") : null;

    try {
      Map<String, String> s3Metadata = new HashMap<>();
      s3Metadata.put("job_id", jobId);
      s3Metadata.put("original_filename", fileName);
      if (userEmail != null) {
        s3Metadata.put("user_email", userEmail);
      }
      if (voice != null && !voice.isEmpty()) {
        s3Metadata.put("voice", voice);
        context.getLogger().log("Voice parameter: " + voice);
      }

      final PutObjectRequest putObjectRequest = PutObjectRequest.builder()
          .bucket(originalBucketName)
          .key(s3Key)
          .contentType(fileContentType)
          .metadata(s3Metadata)
          .build();

      final PutObjectResponse putObjectResponse = s3Client.putObject(
          putObjectRequest, RequestBody.fromBytes(fileBytes));

      context.getLogger().log("File upload successful: " +
          putObjectResponse.sdkHttpResponse().isSuccessful());

    } catch (Exception e) {
      context.getLogger().log("S3 upload failed: " + e.getMessage());
      return respJson(500, Map.of("error", "Failed to upload file to storage"));
    }

    // Write job status to DynamoDB with user email for notifications
    if (jobStatusTable != null) {
      try {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("job_id", AttributeValue.builder().s(jobId).build());
        item.put("status", AttributeValue.builder().s("uploaded").build());
        item.put("original_filename", AttributeValue.builder().s(fileName).build());
        item.put("created_at", AttributeValue.builder().s(Instant.now().toString()).build());
        if (userEmail != null) {
          item.put("user_email", AttributeValue.builder().s(userEmail).build());
        }
        // TTL: 30 days from now
        long ttl = Instant.now().getEpochSecond() + (30 * 24 * 3600);
        item.put("ttl", AttributeValue.builder().n(String.valueOf(ttl)).build());

        dynamoDbClient.putItem(PutItemRequest.builder()
            .tableName(jobStatusTable)
            .item(item)
            .build());
        context.getLogger().log("Job status written to DynamoDB: " + jobId);
      } catch (Exception e) {
        context.getLogger().log("WARNING: Failed to write job status to DynamoDB: " + e.getMessage());
        // Don't fail the upload if DynamoDB write fails
      }
    }

    return respJson(200, Map.of(
        "jobId", jobId,
        "fileName", fileName,
        "fileSize", fileBytes.length,
        "message", "File validated and submitted for processing"));
  }

  /**
   * Extract email from Cognito JWT token. API Gateway already validated the token,
   * so we just base64-decode the payload to read the 'email' claim.
   */
  private String extractEmailFromJwt(Map<String, String> headers, Context context) {
    String authHeader = header(headers, "authorization");
    if (authHeader == null) {
      context.getLogger().log("No Authorization header found");
      return null;
    }
    // Strip "Bearer " prefix if present
    String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
    String[] parts = token.split("\\.");
    if (parts.length < 2) {
      context.getLogger().log("Invalid JWT format");
      return null;
    }
    try {
      String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
      // Simple JSON parsing for "email" field to avoid adding a JSON library dependency
      int emailIdx = payload.indexOf("\"email\"");
      if (emailIdx == -1) return null;
      int colonIdx = payload.indexOf(':', emailIdx);
      if (colonIdx == -1) return null;
      int quoteStart = payload.indexOf('"', colonIdx + 1);
      if (quoteStart == -1) return null;
      int quoteEnd = payload.indexOf('"', quoteStart + 1);
      if (quoteEnd == -1) return null;
      String email = payload.substring(quoteStart + 1, quoteEnd);
      context.getLogger().log("Extracted user email: " + email);
      return email;
    } catch (Exception e) {
      context.getLogger().log("Failed to extract email from JWT: " + e.getMessage());
      return null;
    }
  }

  public static String extractFileName(String contentDisposition, String contentType) {
    // Try to extract filename from Content-Disposition header
    if (contentDisposition != null) {
      String[] parts = contentDisposition.split(";");
      for (String part : parts) {
        part = part.trim();
        if (part.startsWith("filename=")) {
          String filename = part.substring(9);
          // Remove quotes if present
          if (filename.startsWith("\"") && filename.endsWith("\"")) {
            filename = filename.substring(1, filename.length() - 1);
          }
          return filename;
        }
      }
    }
    
    // Fallback: generate filename based on content type
    String extension;
    String baseContentType = contentType.toLowerCase().split(";")[0].trim();
    switch (baseContentType) {
      case "application/pdf":
        extension = ".pdf";
        break;
      case "application/epub+zip":
        extension = ".epub";
        break;
      case "text/plain":
        extension = ".txt";
        break;
      default:
        extension = "";
    }
    return "upload_" + Instant.now().getEpochSecond() + extension;
  }

  public static String header(Map<String, String> h, String key) {
    if (h == null)
      return null;
    String v = h.get(key);
    if (v != null)
      return v;
    // header names are case-insensitive
    for (var e : h.entrySet()) {
      if (e.getKey() != null && e.getKey().equalsIgnoreCase(key))
        return e.getValue();
    }
    return null;
  }

  // Helper class to hold file upload result
  private static class FileUploadResult {
    final byte[] content;
    final String filename;
    final String contentType;
    
    FileUploadResult(byte[] content, String filename, String contentType) {
      this.content = content;
      this.filename = filename;
      this.contentType = contentType;
    }
  }
  
  // Parse multipart/form-data content
  private FileUploadResult parseMultipartData(String body, String contentType, boolean isBase64, Context context)
      throws IOException {

    try {
      // For multipart/form-data with binary files, API Gateway should always base64 encode
      // If not base64, log a warning as this could corrupt binary data
      if (!isBase64) {
        context.getLogger().log("WARNING: Multipart data is not base64 encoded. Binary files may be corrupted.");
      }

      byte[] bodyBytes = isBase64 ? Base64.getDecoder().decode(body) : body.getBytes(StandardCharsets.ISO_8859_1);
    
      // Extract boundary from content-type header
      String boundary = null;
      String[] parts = contentType.split(";");
      for (String part : parts) {
        part = part.trim();
        if (part.startsWith("boundary=")) {
          boundary = part.substring(9);
          break;
        }
      }

      if (boundary == null) {
        throw new IllegalArgumentException("No boundary found in content-type header");
      }
    
      // Binary-safe multipart parsing to preserve PDF content
      String boundaryMarker = "--" + boundary;
      byte[] boundaryBytes = boundaryMarker.getBytes(StandardCharsets.UTF_8);

      int start = 0;
      while (start < bodyBytes.length) {
        // Find next boundary
        int boundaryIndex = indexOf(bodyBytes, boundaryBytes, start);
        if (boundaryIndex == -1) break;

        // Move past the boundary
        start = boundaryIndex + boundaryBytes.length;
        if (start >= bodyBytes.length) break;

        // Skip CRLF after boundary
        if (start + 1 < bodyBytes.length && bodyBytes[start] == '\r' && bodyBytes[start + 1] == '\n') {
          start += 2;
        }

        // Find next boundary to get section end
        int nextBoundaryIndex = indexOf(bodyBytes, boundaryBytes, start);
        if (nextBoundaryIndex == -1) nextBoundaryIndex = bodyBytes.length;

        // Extract section data
        byte[] sectionData = new byte[nextBoundaryIndex - start];
        System.arraycopy(bodyBytes, start, sectionData, 0, sectionData.length);

        // Parse headers as string (headers are always text)
        String headers = new String(sectionData, 0, Math.min(sectionData.length, 500), StandardCharsets.UTF_8);
        if (headers.contains("Content-Disposition:") && headers.contains("filename=")) {
          String filename = null;
          String fileContentType = "application/octet-stream";

          // Extract filename
          int filenameStart = headers.indexOf("filename=\"");
          if (filenameStart != -1) {
            filenameStart += 10;
            int filenameEnd = headers.indexOf("\"", filenameStart);
            if (filenameEnd > filenameStart) {
              filename = headers.substring(filenameStart, filenameEnd);
            }
          }

          // Extract content-type
          int ctStart = headers.indexOf("Content-Type:");
          if (ctStart != -1) {
            ctStart += 13;
            int ctEnd = headers.indexOf("\r\n", ctStart);
            if (ctEnd != -1) {
              fileContentType = headers.substring(ctStart, ctEnd).trim();
            }
          }

          // Find end of headers (double CRLF) using binary search
          byte[] headerEndPattern = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);
          int headerEnd = indexOf(sectionData, headerEndPattern, 0);

          if (headerEnd != -1 && filename != null) {
            // Extract binary file content without string conversion
            int contentStart = headerEnd + 4;
            int contentLength = sectionData.length - contentStart;

            // Remove trailing CRLF if present (but preserve all other binary data)
            if (contentLength >= 2 &&
                sectionData[contentStart + contentLength - 2] == '\r' &&
                sectionData[contentStart + contentLength - 1] == '\n') {
              contentLength -= 2;
            }

            byte[] fileContent = new byte[contentLength];
            System.arraycopy(sectionData, contentStart, fileContent, 0, contentLength);

            context.getLogger().log("Parsed multipart file: " + filename + ", type: " + fileContentType + ", size: " + fileContent.length);

            return new FileUploadResult(fileContent, filename, fileContentType);
          }
        }

        start = nextBoundaryIndex;
      }

      throw new IllegalArgumentException("No file found in multipart data");

    } catch (Exception e) {
      context.getLogger().log("Error parsing multipart data: " + e.getMessage());
      throw new IOException("Failed to parse multipart data", e);
    }
  }
  
  // Helper method to find byte pattern in byte array
  private int indexOf(byte[] data, byte[] pattern, int start) {
    for (int i = start; i <= data.length - pattern.length; i++) {
      boolean found = true;
      for (int j = 0; j < pattern.length; j++) {
        if (data[i + j] != pattern[j]) {
          found = false;
          break;
        }
      }
      if (found) return i;
    }
    return -1;
  }

  // Check if file is allowed based on extension
  private boolean isAllowedFile(String filename, String contentType) {
    if (filename == null) return false;

    String lowerFilename = filename.toLowerCase();
    return ALLOWED_FILE_EXTENSIONS.stream().anyMatch(lowerFilename::endsWith) ||
           (contentType != null && ALLOWED_CT.stream().anyMatch(allowed ->
               contentType.toLowerCase().startsWith(allowed.toLowerCase())));
  }

  private static APIGatewayProxyResponseEvent resp(int status, String msg) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(status)
        .withHeaders(Map.of(
            "Content-Type", "application/json",
            "Access-Control-Allow-Origin", "*"))
        .withBody("{\"error\":\"" + msg.replace("\"", "\\\"") + "\"}");
  }

  private static APIGatewayProxyResponseEvent respJson(int status, Map<String, Object> data) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (var entry : data.entrySet()) {
      if (!first) sb.append(",");
      first = false;
      sb.append("\"").append(entry.getKey()).append("\":");
      Object val = entry.getValue();
      if (val instanceof Number) {
        sb.append(val);
      } else {
        sb.append("\"").append(val.toString().replace("\"", "\\\"")).append("\"");
      }
    }
    sb.append("}");
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(status)
        .withHeaders(Map.of(
            "Content-Type", "application/json",
            "Access-Control-Allow-Origin", "*"))
        .withBody(sb.toString());
  }
}
