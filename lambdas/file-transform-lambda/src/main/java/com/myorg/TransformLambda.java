package com.myorg;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class TransformLambda implements RequestHandler<Map<String, Object>, String> {
        private static final String ORIGINAL_BUCKET_NAME = System.getenv("ORIGINAL_BUCKET_NAME");
        private static final String MARKDOWN_BUCKET_NAME = System.getenv("MARKDOWN_BUCKET_NAME");
        private static final String CHAPTERS_BUCKET_NAME = System.getenv("CHAPTERS_BUCKET_NAME");
        private static final String TTS_PROVIDER = System.getenv("TTS_PROVIDER") != null ? System.getenv("TTS_PROVIDER") : "OPENAI";
        private static final String MISTRAL_API_KEY = System.getenv("MISTRAL_API_KEY");
        private static final String MISTRAL_BASE_URL = "https://api.mistral.ai/v1";

        private final S3Client s3Client = S3Client.builder().region(Region.US_EAST_1).build();
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final CloseableHttpClient httpClient = HttpClients.createDefault();

        @Override
        public String handleRequest(Map<String, Object> event, com.amazonaws.services.lambda.runtime.Context context) {
            context.getLogger().log("Initiating Transform lambda function with Mistral OCR processing");
            context.getLogger().log("Received event: " + event);

            if (MISTRAL_API_KEY == null || MISTRAL_API_KEY.isEmpty()) {
                context.getLogger().log("ERROR: MISTRAL_API_KEY environment variable is not set");
                return "Error: Missing Mistral API key";
            }

            if (MARKDOWN_BUCKET_NAME == null || MARKDOWN_BUCKET_NAME.isEmpty()) {
                context.getLogger().log("ERROR: MARKDOWN_BUCKET_NAME environment variable is not set");
                return "Error: Missing markdown bucket name";
            }

            // get the filename from the event
            final List<Map<String, Object>> records = (List<Map<String, Object>>) event.get("Records");

            final String fileName = records.stream()
                .map(record -> (Map<String, Object>) record.get("s3"))
                .map(s3 -> (Map<String, Object>) s3.get("object"))
                .map(object -> (String) object.get("key"))
                .findFirst()
                .orElse(null);

            if(fileName == null) {
                context.getLogger().log("Invalid request: No file name found.");
                return "Invalid request: No file name found.";
            }

            context.getLogger().log("Processing file with OCR: " + fileName);

            try {
                // Step 1: Download file from S3 and upload to Mistral for file hosting
                final byte[] fileContent = downloadFileFromS3(fileName, context);
                final String fileId = uploadFileToMistralAPI(fileName, fileContent, context);

                if (fileId == null) {
                    return "Error: Failed to upload file to Mistral API";
                }

                // Step 2: Get file URL for OCR processing
                final String fileUrl = getFileUrlFromMistralAPI(fileId, 24, context);

                if (fileUrl == null) {
                    return "Error: Failed to retrieve file URL from Mistral API";
                }

                // Step 3: Process file with OCR
                final JsonNode ocrResponse = processFileWithOCR(fileUrl, context);

                if (ocrResponse == null) {
                    return "Error: Failed to process file with OCR";
                }

                // Step 4: Extract and order markdown content
                final String consolidatedMarkdown = extractAndOrderMarkdown(ocrResponse, context);

                if (consolidatedMarkdown == null || consolidatedMarkdown.trim().isEmpty()) {
                    return "Error: Failed to extract markdown content from OCR response";
                }

                // Step 5: Upload consolidated markdown to S3
                // Route based on TTS provider - VibeVoice goes directly to chapters bucket
                final boolean uploadSuccessful;
                if ("VIBEVOICE".equalsIgnoreCase(TTS_PROVIDER)) {
                    context.getLogger().log("Using VibeVoice provider - uploading directly to chapters bucket for TTS processing");
                    uploadSuccessful = uploadDirectlyForTts(consolidatedMarkdown, fileName, context);
                } else {
                    context.getLogger().log("Using OpenAI provider - uploading to markdown bucket for chapter splitting");
                    uploadSuccessful = uploadMarkdownToS3(consolidatedMarkdown, fileName, context);
                }

                if (!uploadSuccessful) {
                    return "Error: Failed to upload markdown file to S3";
                }

                String markdownFileName = generateMarkdownFileName(fileName);
                context.getLogger().log("OCR processing completed successfully. Generated: " + markdownFileName);

                return String.format("OCR processing completed successfully. Markdown file created: %s", markdownFileName);

            } catch (Exception e) {
                context.getLogger().log("ERROR processing file: " + e.getMessage());
                return "Error: " + e.getMessage();
            }
        }

        private byte[] downloadFileFromS3(String fileName, com.amazonaws.services.lambda.runtime.Context context) throws IOException {
            context.getLogger().log("Downloading file from S3: " + fileName);

            final GetObjectRequest objectRequest = GetObjectRequest.builder()
                    .bucket(ORIGINAL_BUCKET_NAME)
                    .key(fileName)
                    .build();

            final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            final GetObjectResponse s3Object = s3Client.getObject(objectRequest, ResponseTransformer.toOutputStream(outputStream));

            final boolean successful = s3Object.sdkHttpResponse().isSuccessful();
            context.getLogger().log("S3 download successful: " + successful);

            if (!successful) {
                throw new IOException("Failed to download file from S3");
            }

            return outputStream.toByteArray();
        }

        private String uploadFileToMistralAPI(String fileName, byte[] fileContent, com.amazonaws.services.lambda.runtime.Context context) {
            context.getLogger().log("Uploading file to Mistral API: " + fileName);

            try {
                HttpPost uploadRequest = new HttpPost(MISTRAL_BASE_URL + "/files");
                uploadRequest.setHeader("Authorization", "Bearer " + MISTRAL_API_KEY);

                MultipartEntityBuilder builder = MultipartEntityBuilder.create();
                builder.addTextBody("purpose", "ocr");
                builder.addBinaryBody("file", fileContent, ContentType.APPLICATION_OCTET_STREAM, fileName);

                uploadRequest.setEntity(builder.build());

                try (CloseableHttpResponse response = httpClient.execute(uploadRequest)) {
                    String responseBody = EntityUtils.toString(response.getEntity());
                    context.getLogger().log("Mistral API upload response: " + responseBody);
                    context.getLogger().log("Response status: " + response.getCode());

                    if (response.getCode() == 200 || response.getCode() == 201) {
                        JsonNode jsonResponse = objectMapper.readTree(responseBody);
                        String fileId = jsonResponse.get("id").asText();
                        context.getLogger().log("File uploaded successfully. File ID: " + fileId);
                        return fileId;
                    } else {
                        context.getLogger().log("ERROR: Upload failed with status " + response.getCode());
                        return null;
                    }
                }
            } catch (Exception e) {
                context.getLogger().log("ERROR uploading file to Mistral API: " + e.getMessage());
                return null;
            }
        }

        private String getFileUrlFromMistralAPI(String fileId, int expiryHours, com.amazonaws.services.lambda.runtime.Context context) {
            context.getLogger().log("Retrieving file URL from Mistral API. File ID: " + fileId);

            try {
                HttpGet urlRequest = new HttpGet(MISTRAL_BASE_URL + "/files/" + fileId + "/url?expiry=" + expiryHours);
                urlRequest.setHeader("Accept", "application/json");
                urlRequest.setHeader("Authorization", "Bearer " + MISTRAL_API_KEY);

                try (CloseableHttpResponse response = httpClient.execute(urlRequest)) {
                    String responseBody = EntityUtils.toString(response.getEntity());
                    context.getLogger().log("Mistral API URL response: " + responseBody);
                    context.getLogger().log("Response status: " + response.getCode());

                    if (response.getCode() == 200) {
                        JsonNode jsonResponse = objectMapper.readTree(responseBody);
                        String fileUrl = jsonResponse.get("url").asText();
                        context.getLogger().log("File URL retrieved successfully: " + fileUrl);
                        return fileUrl;
                    } else {
                        context.getLogger().log("ERROR: URL retrieval failed with status " + response.getCode());
                        return null;
                    }
                }
            } catch (Exception e) {
                context.getLogger().log("ERROR retrieving file URL from Mistral API: " + e.getMessage());
                return null;
            }
        }

        private JsonNode processFileWithOCR(String fileUrl, com.amazonaws.services.lambda.runtime.Context context) {
            context.getLogger().log("Processing file with OCR API. File URL: " + fileUrl);

            try {
                HttpPost ocrRequest = new HttpPost(MISTRAL_BASE_URL + "/ocr");
                ocrRequest.setHeader("Authorization", "Bearer " + MISTRAL_API_KEY);
                ocrRequest.setHeader("Content-Type", "application/json");

                // Use Jackson to create properly formatted JSON to avoid encoding issues
                String requestBody;
                try {
                    var requestMap = Map.of(
                        "model", "mistral-ocr-latest",
                        "document", Map.of(
                            "type", "document_url",
                            "document_url", fileUrl
                        ),
                        "include_image_base64", false
                    );
                    requestBody = objectMapper.writeValueAsString(requestMap);
                } catch (Exception e) {
                    context.getLogger().log("ERROR: Failed to create JSON request body: " + e.getMessage());
                    return null;
                }

                context.getLogger().log("OCR request body: " + requestBody);
                context.getLogger().log("File URL being used: " + fileUrl);

                ocrRequest.setEntity(new StringEntity(requestBody, StandardCharsets.UTF_8));
                ocrRequest.setHeader("Accept", "application/json");

                try (CloseableHttpResponse response = httpClient.execute(ocrRequest)) {
                    String responseBody = EntityUtils.toString(response.getEntity());
                    context.getLogger().log("OCR API response status: " + response.getCode());
                    context.getLogger().log("OCR API response: " + responseBody);

                    if (response.getCode() == 200) {
                        JsonNode jsonResponse = objectMapper.readTree(responseBody);
                        context.getLogger().log("OCR processing successful");
                        return jsonResponse;
                    } else {
                        context.getLogger().log("ERROR: OCR processing failed with status " + response.getCode());
                        return null;
                    }
                }
            } catch (Exception e) {
                context.getLogger().log("ERROR processing file with OCR: " + e.getMessage());
                return null;
            }
        }

        private String extractAndOrderMarkdown(JsonNode ocrResponse, com.amazonaws.services.lambda.runtime.Context context) {
            context.getLogger().log("Extracting and ordering markdown content from OCR response");

            try {
                List<MarkdownSegment> segments = new ArrayList<>();

                // Check if response has 'pages' array
                if (ocrResponse.has("pages") && ocrResponse.get("pages").isArray()) {
                    JsonNode pages = ocrResponse.get("pages");
                    context.getLogger().log("Found " + pages.size() + " pages in OCR response");

                    for (JsonNode page : pages) {
                        if (page.has("index") && page.has("markdown")) {
                            int index = page.get("index").asInt();
                            String markdown = page.get("markdown").asText();

                            // Skip empty or minimal markdown content
                            if (markdown != null && !markdown.trim().equals(".") && !markdown.trim().isEmpty()) {
                                segments.add(new MarkdownSegment(index, markdown));
                                context.getLogger().log("Extracted page " + index + " with " + markdown.length() + " characters");
                            } else {
                                context.getLogger().log("Skipping page " + index + " with minimal content: '" + markdown + "'");
                            }
                        }
                    }
                } else if (ocrResponse.isArray()) {
                    // Fallback: direct array format
                    for (JsonNode item : ocrResponse) {
                        if (item.has("index") && item.has("markdown")) {
                            int index = item.get("index").asInt();
                            String markdown = item.get("markdown").asText();
                            if (markdown != null && !markdown.trim().equals(".") && !markdown.trim().isEmpty()) {
                                segments.add(new MarkdownSegment(index, markdown));
                                context.getLogger().log("Extracted segment " + index + " with " + markdown.length() + " characters");
                            }
                        }
                    }
                } else {
                    context.getLogger().log("ERROR: Expected 'pages' array or direct array response from OCR API");
                    context.getLogger().log("Response structure: " + ocrResponse.toPrettyString());
                    return null;
                }

                if (segments.isEmpty()) {
                    context.getLogger().log("WARNING: No valid markdown segments found - all pages contained minimal content");
                    return null;
                }

                segments.sort(Comparator.comparingInt(segment -> segment.index));
                context.getLogger().log("Sorted " + segments.size() + " markdown segments by index");

                StringBuilder consolidatedMarkdown = new StringBuilder();
                for (MarkdownSegment segment : segments) {
                    consolidatedMarkdown.append(segment.markdown);
                    if (!segment.markdown.endsWith("\n")) {
                        consolidatedMarkdown.append("\n");
                    }
                }

                String result = consolidatedMarkdown.toString();
                context.getLogger().log("Consolidated markdown content: " + result.length() + " total characters");
                if (result.length() < 100) {
                    context.getLogger().log("Short markdown content preview: " + result);
                }
                return result;

            } catch (Exception e) {
                context.getLogger().log("ERROR extracting markdown: " + e.getMessage());
                return null;
            }
        }

        private boolean uploadMarkdownToS3(String markdownContent, String originalFileName, com.amazonaws.services.lambda.runtime.Context context) {
            context.getLogger().log("Uploading consolidated markdown to S3");

            try {
                String markdownFileName = generateMarkdownFileName(originalFileName);
                context.getLogger().log("Generated markdown filename: " + markdownFileName);

                PutObjectRequest putRequest = PutObjectRequest.builder()
                        .bucket(MARKDOWN_BUCKET_NAME)
                        .key(markdownFileName)
                        .contentType("text/markdown")
                        .build();

                RequestBody requestBody = RequestBody.fromString(markdownContent);

                var response = s3Client.putObject(putRequest, requestBody);

                boolean successful = response.sdkHttpResponse().isSuccessful();
                context.getLogger().log("Markdown upload successful: " + successful);

                if (successful) {
                    context.getLogger().log("Markdown file uploaded successfully: " + markdownFileName);
                    context.getLogger().log("File size: " + markdownContent.length() + " characters");
                }

                return successful;

            } catch (Exception e) {
                context.getLogger().log("ERROR uploading markdown to S3: " + e.getMessage());
                return false;
            }
        }

        private String generateMarkdownFileName(String originalFileName) {
            int lastDotIndex = originalFileName.lastIndexOf('.');
            if (lastDotIndex > 0) {
                return originalFileName.substring(0, lastDotIndex) + ".md";
            } else {
                return originalFileName + ".md";
            }
        }

        private boolean uploadDirectlyForTts(String markdownContent, String originalFileName, com.amazonaws.services.lambda.runtime.Context context) {
            context.getLogger().log("Uploading markdown directly to chapters bucket for VibeVoice TTS processing");

            try {
                String markdownFileName = generateMarkdownFileName(originalFileName);
                String baseName = markdownFileName.replace(".md", "");

                // Upload the full markdown as a single "chapter" for TTS to process
                String chapterKey = baseName + "/001.md";
                context.getLogger().log("Generated chapter key: " + chapterKey);

                PutObjectRequest putRequest = PutObjectRequest.builder()
                        .bucket(CHAPTERS_BUCKET_NAME)
                        .key(chapterKey)
                        .contentType("text/markdown")
                        .metadata(Map.of(
                            "source-file", originalFileName,
                            "tts-provider", TTS_PROVIDER
                        ))
                        .build();

                RequestBody requestBody = RequestBody.fromString(markdownContent);
                var response = s3Client.putObject(putRequest, requestBody);

                boolean successful = response.sdkHttpResponse().isSuccessful();
                context.getLogger().log("Direct TTS upload successful: " + successful);

                if (successful) {
                    context.getLogger().log("Markdown file uploaded directly to chapters bucket: " + chapterKey);
                    context.getLogger().log("File size: " + markdownContent.length() + " characters");
                }

                return successful;

            } catch (Exception e) {
                context.getLogger().log("ERROR uploading directly to chapters bucket: " + e.getMessage());
                return false;
            }
        }

        private static class MarkdownSegment {
            final int index;
            final String markdown;

            MarkdownSegment(int index, String markdown) {
                this.index = index;
                this.markdown = markdown;
            }
        }

}
