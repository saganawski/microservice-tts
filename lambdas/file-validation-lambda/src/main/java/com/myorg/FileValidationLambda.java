package com.myorg;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.core.sync.RequestBody;
import com.amazonaws.services.lambda.runtime.Context;
import java.time.Instant;
import java.util.UUID;


//import static software.amazon.awssdk.http.auth.aws.internal.signer.V4RequestSigner.header;

//public class FileValidationLambda implements RequestHandler<Map<String, Object>, String> {
public class FileValidationLambda implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
  private final S3Client s3Client;
  private final String originalBucketName;
  private static final Set<String> ALLOWED_CT = Set.of("application/pdf", "application/epub+zip", "text/plain");

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
    // Map<String, String> queryStringParameters = event.getQueryStringParameters();
    final String contentType = header(headers, "content-type");
    final String contentDisposition = header(headers, "content-disposition");
    final boolean isBase64 = Boolean.TRUE.equals((event.getIsBase64Encoded()));
    String body = event.getBody();

    if (body == null) {
      return resp(400, "No body received");
    }

    if (contentType == null || ALLOWED_CT.stream().noneMatch(contentType::equalsIgnoreCase)) {
      return resp(415, "Unsupported media type. Allowed: application/pdf, application/epub+zip, text/plain");
    }

    byte[] fileBytes = isBase64 ? Base64.getDecoder().decode(body) : body.getBytes(StandardCharsets.ISO_8859_1);
    
    String fileName = extractFileName(contentDisposition, contentType);
    context.getLogger().log("Using filename: " + fileName);
    
    try {
      final PutObjectRequest putObjectRequest = PutObjectRequest.builder()
          .bucket(originalBucketName)
          .key(fileName)
          .contentType(contentType)
          .build();
      
      final PutObjectResponse putObjectResponse = s3Client.putObject(
          putObjectRequest, RequestBody.fromBytes(fileBytes));

      context.getLogger().log("File upload successful: " + 
          putObjectResponse.sdkHttpResponse().isSuccessful());
          
    } catch (Exception e) {
      context.getLogger().log("S3 upload failed: " + e.getMessage());
      return resp(500, "Failed to upload file to storage");
    }

    return resp(200, "Received " + fileBytes.length + " bytes; contentType=" + contentType
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
    switch (contentType.toLowerCase()) {
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

  private static APIGatewayProxyResponseEvent resp(int status, String msg) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(status)
        .withHeaders(Map.of(
            "Content-Type", "text/plain",
            "Access-Control-Allow-Origin", "*"))
        .withBody(msg);
  }
}
