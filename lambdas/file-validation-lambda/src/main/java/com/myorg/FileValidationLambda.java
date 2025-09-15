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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

public class FileValidationLambda implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
  private final S3Client s3Client;
  private final String originalBucketName;
  private static final Set<String> ALLOWED_CT = Set.of("application/pdf", "application/epub+zip", "text/plain");
  private static final Set<String> ALLOWED_FILE_EXTENSIONS = Set.of(".pdf", ".epub", ".txt");

  public FileValidationLambda() {
    this(S3Client.builder().region(Region.US_EAST_1).build(), System.getenv("ORIGINAL_BUCKET_NAME"));
  }

  // Constructor for testing
  public FileValidationLambda(S3Client s3Client, String originalBucketName) {
    this.s3Client = s3Client;
    this.originalBucketName = originalBucketName;
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
    
    try {
      final PutObjectRequest putObjectRequest = PutObjectRequest.builder()
          .bucket(originalBucketName)
          .key(fileName)
          .contentType(fileContentType)
          .build();
      
      final PutObjectResponse putObjectResponse = s3Client.putObject(
          putObjectRequest, RequestBody.fromBytes(fileBytes));

      context.getLogger().log("File upload successful: " + 
          putObjectResponse.sdkHttpResponse().isSuccessful());
          
    } catch (Exception e) {
      context.getLogger().log("S3 upload failed: " + e.getMessage());
      return resp(500, "Failed to upload file to storage");
    }

    return resp(200, "Received " + fileBytes.length + " bytes; contentType=" + fileContentType
        + " Successfully validated and uploaded to the next step");
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
            "Content-Type", "text/plain",
            "Access-Control-Allow-Origin", "*"))
        .withBody(msg);
  }
}
