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
    
    // Simple multipart parsing for file uploads
    String bodyString = new String(bodyBytes, StandardCharsets.ISO_8859_1);
    String[] sections = bodyString.split("--" + boundary);
    
    for (String section : sections) {
      if (section.contains("Content-Disposition: form-data") && section.contains("filename=")) {
        // Parse the file section
        String[] lines = section.split("\r\n");
        String filename = null;
        String fileContentType = "text/plain"; // default
        int contentStart = -1;
        
        for (int i = 0; i < lines.length; i++) {
          String line = lines[i];
          if (line.contains("Content-Disposition:") && line.contains("filename=")) {
            // Extract filename
            int filenameStart = line.indexOf("filename=\"") + 10;
            int filenameEnd = line.indexOf("\"", filenameStart);
            if (filenameEnd > filenameStart) {
              filename = line.substring(filenameStart, filenameEnd);
            }
          } else if (line.startsWith("Content-Type:")) {
            fileContentType = line.substring(13).trim();
          } else if (line.trim().isEmpty() && i > 0) {
            contentStart = i + 1;
            break;
          }
        }
        
        if (filename != null && contentStart >= 0) {
          // Extract file content
          StringBuilder contentBuilder = new StringBuilder();
          for (int i = contentStart; i < lines.length; i++) {
            if (i > contentStart) {
              contentBuilder.append("\r\n");
            }
            contentBuilder.append(lines[i]);
          }
          
          String content = contentBuilder.toString();
          // Remove trailing boundary marker if present
          if (content.endsWith("\r\n--")) {
            content = content.substring(0, content.length() - 4);
          } else if (content.endsWith("--")) {
            content = content.substring(0, content.length() - 2);
          }
          
          byte[] fileContent = content.getBytes(StandardCharsets.ISO_8859_1);
          context.getLogger().log("Parsed multipart file: " + filename + ", size: " + fileContent.length);
          
          return new FileUploadResult(fileContent, filename, fileContentType);
        }
      }
    }
    
    throw new IllegalArgumentException("No file found in multipart data");
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
