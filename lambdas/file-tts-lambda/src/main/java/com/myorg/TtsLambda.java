package com.myorg;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
//import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;



public class TtsLambda implements RequestHandler<Map<String, Object>, String> {
    private static final String MARKDOWN_BUCKET_NAME = System.getenv("MARKDOWN_BUCKET_NAME");
    private static final String PROCESSED_BUCKET_NAME = System.getenv("PROCESSED_BUCKET_NAME");
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    private static final int CHUNK_SIZE = 900;

    private final S3Client s3Client;
    private final HttpClient httpClient;
//    private final ObjectMapper objectMapper;

    public TtsLambda() {
        this.s3Client = S3Client.builder().build();
        this.httpClient = HttpClient.newHttpClient();
//        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        context.getLogger().log("Starting TTS processing with markdown chunking");
        context.getLogger().log("Lambda timeout: " + context.getRemainingTimeInMillis() + "ms");
        context.getLogger().log("Received event: " + event);

        if (MARKDOWN_BUCKET_NAME == null || MARKDOWN_BUCKET_NAME.isEmpty()) {
            context.getLogger().log("ERROR: MARKDOWN_BUCKET_NAME environment variable is not set");
            throw new RuntimeException("Missing markdown bucket name");
        }

        if (PROCESSED_BUCKET_NAME == null || PROCESSED_BUCKET_NAME.isEmpty()) {
            context.getLogger().log("ERROR: PROCESSED_BUCKET_NAME environment variable is not set");
            throw new RuntimeException("Missing processed bucket name");
        }

        try {
            // Extract bucket and key from S3 event
            String bucketName = extractBucketName(event);
            String objectKey = extractObjectKey(event);

            context.getLogger().log("Processing markdown file: " + objectKey + " from bucket: " + bucketName);

            // Download markdown file from S3
            String markdownContent = downloadMarkdownFromS3(bucketName, objectKey, context);
            context.getLogger().log("Downloaded markdown content, length: " + markdownContent.length());

            // Chunk the markdown content into 900-character segments
            List<String> chunks = chunkMarkdownContent(markdownContent, context);
            context.getLogger().log("Split markdown into " + chunks.size() + " chunks of max " + CHUNK_SIZE + " characters each");

            // Process each chunk with TTS
/*            List<String> audioFiles = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                context.getLogger().log("Processing chunk " + (i + 1) + "/" + chunks.size() + " (length: " + chunk.length() + ")");

                // Check remaining time before API call
                context.getLogger().log("Time remaining before TTS API call: " + context.getRemainingTimeInMillis() + "ms");

                // Convert chunk to speech using OpenAI API
                byte[] audioData = convertTextToSpeech(chunk, context);
                context.getLogger().log("Generated audio data for chunk " + (i + 1) + ", size: " + audioData.length + " bytes");

                // Upload MP3 to processed bucket
                String outputKey = generateChunkOutputKey(objectKey, i);
                uploadAudioToS3(outputKey, audioData, context);
                audioFiles.add(outputKey);
                context.getLogger().log("Successfully uploaded audio file: " + outputKey);
            }*/
            String result = "Successfully processed " + chunks.size() + " chunks from markdown file. Generated Dummy test: " ;
//            String result = "Successfully processed " + chunks.size() + " chunks from markdown file. Generated audio files: " + String.join(", ", audioFiles);
            context.getLogger().log(result);
            return result;

        } catch (Exception e) {
            context.getLogger().log("Error processing TTS: " + e.getMessage());
            throw new RuntimeException("Failed to process TTS", e);
        }
    }


    private byte[] convertTextToSpeech(String textContent, Context context) throws IOException, InterruptedException {
        context.getLogger().log("Converting text to speech using OpenAI API");

        if (OPENAI_API_KEY == null || OPENAI_API_KEY.isEmpty()) {
            throw new IllegalStateException("OPENAI_API_KEY environment variable is not set");
        }

        // Create request body
        String requestBody = String.format("""
                {
                    "model": "gpt-4o-mini-tts",
                    "input": "%s",
                    "voice": "alloy"
                }
                """, textContent.replace("\"", "\\\"").replace("\n", "\\n"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/audio/speech"))
                .header("Authorization", "Bearer " + OPENAI_API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        context.getLogger().log("Sending request to OpenAI TTS API");
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            String errorBody = new String(response.body(), StandardCharsets.UTF_8);
            context.getLogger().log("OpenAI API error: " + response.statusCode() + " - " + errorBody);
            throw new RuntimeException("OpenAI API request failed: " + response.statusCode() + " - " + errorBody);
        }

        return response.body();
    }

    private String generateChunkOutputKey(String inputKey, int chunkIndex) {
        // Remove .md extension and add chunk index and .mp3
        String baseName = inputKey.replaceAll("\\.md$", "");
        return String.format("%s_chunk_%03d.mp3", baseName, chunkIndex + 1);
    }

    private void uploadAudioToS3(String objectKey, byte[] audioData, Context context) {
        context.getLogger().log("Starting S3 upload process...");
        context.getLogger().log("Target bucket: " + PROCESSED_BUCKET_NAME);
        context.getLogger().log("Object key: " + objectKey);
        context.getLogger().log("Audio data size: " + audioData.length + " bytes");
        context.getLogger().log("Time remaining before upload: " + context.getRemainingTimeInMillis() + "ms");
        
        try {
            // Validate inputs
            if (PROCESSED_BUCKET_NAME == null || PROCESSED_BUCKET_NAME.isEmpty()) {
                throw new IllegalStateException("PROCESSED_BUCKET_NAME environment variable is not set");
            }
            if (objectKey == null || objectKey.isEmpty()) {
                throw new IllegalArgumentException("Object key cannot be null or empty");
            }
            if (audioData == null || audioData.length == 0) {
                throw new IllegalArgumentException("Audio data cannot be null or empty");
            }
            
            context.getLogger().log("Building PutObjectRequest...");
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(PROCESSED_BUCKET_NAME)
                    .key(objectKey)
                    .contentType("audio/mpeg")
                    .contentLength((long) audioData.length)
                    .build();
            
            context.getLogger().log("PutObjectRequest built successfully");
            context.getLogger().log("Executing S3 putObject...");
            
            final PutObjectResponse putObjectResponse = s3Client.putObject(putObjectRequest, 
                    RequestBody.fromInputStream(new ByteArrayInputStream(audioData), audioData.length));
            
            context.getLogger().log("S3 putObject call completed");
            context.getLogger().log("HTTP Response Code: " + putObjectResponse.sdkHttpResponse().statusCode());
            context.getLogger().log("HTTP Response Success: " + putObjectResponse.sdkHttpResponse().isSuccessful());
            context.getLogger().log("ETag: " + putObjectResponse.eTag());
            context.getLogger().log("Request ID: " + putObjectResponse.responseMetadata().requestId());
            context.getLogger().log("Time remaining after upload: " + context.getRemainingTimeInMillis() + "ms");
            
            if (putObjectResponse.sdkHttpResponse().isSuccessful()) {
                context.getLogger().log("✓ Successfully uploaded audio file: " + objectKey);
            } else {
                context.getLogger().log("✗ Upload appears to have failed despite no exception");
            }
            
        } catch (Exception e) {
            context.getLogger().log("ERROR during S3 upload: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            if (e.getCause() != null) {
                context.getLogger().log("Caused by: " + e.getCause().getClass().getSimpleName() + " - " + e.getCause().getMessage());
            }
            
            // Log stack trace for debugging
            StringBuilder stackTrace = new StringBuilder();
            for (StackTraceElement elem : e.getStackTrace()) {
                stackTrace.append(elem.toString()).append("\n");
            }
            context.getLogger().log("Stack trace: " + stackTrace.toString());
            
            throw new RuntimeException("Failed to upload audio file to S3", e);
        }
    }

    private String extractBucketName(Map<String, Object> event) {
        Map<String, Object> records = (Map<String, Object>) ((java.util.List<?>) event.get("Records")).get(0);
        Map<String, Object> s3 = (Map<String, Object>) records.get("s3");
        Map<String, Object> bucket = (Map<String, Object>) s3.get("bucket");
        return (String) bucket.get("name");
    }

    private String extractObjectKey(Map<String, Object> event) {
        Map<String, Object> records = (Map<String, Object>) ((java.util.List<?>) event.get("Records")).get(0);
        Map<String, Object> s3 = (Map<String, Object>) records.get("s3");
        Map<String, Object> object = (Map<String, Object>) s3.get("object");
        return (String) object.get("key");
    }

    private String downloadMarkdownFromS3(String bucketName, String objectKey, Context context) throws IOException {
        context.getLogger().log("Downloading markdown file from S3: " + bucketName + "/" + objectKey);

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build();

        ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(getObjectRequest);
        return objectBytes.asString(StandardCharsets.UTF_8);
    }

    private List<String> chunkMarkdownContent(String markdownContent, Context context) {
        context.getLogger().log("Chunking markdown content into " + CHUNK_SIZE + " character segments");

        List<String> chunks = new ArrayList<>();

        if (markdownContent == null || markdownContent.trim().isEmpty()) {
            context.getLogger().log("WARNING: Markdown content is empty");
            return chunks;
        }

        String content = markdownContent.trim();

        if (content.length() <= CHUNK_SIZE) {
            chunks.add(content);
            context.getLogger().log("Content fits in single chunk: " + content.length() + " characters");
            return chunks;
        }

        int start = 0;
        int chunkNumber = 1;

        while (start < content.length()) {
            int end = Math.min(start + CHUNK_SIZE, content.length());

            // Try to break at word boundaries to avoid cutting words
            if (end < content.length()) {
                // Look for the last space, newline, or punctuation within the chunk
                int lastSpace = Math.max(
                    Math.max(content.lastIndexOf(' ', end), content.lastIndexOf('\n', end)),
                    Math.max(content.lastIndexOf('.', end), content.lastIndexOf(',', end))
                );

                if (lastSpace > start) {
                    end = lastSpace;
                }
            }

            String chunk = content.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
                context.getLogger().log("Created chunk " + chunkNumber + ": " + chunk.length() + " characters");
                chunkNumber++;
            }

            // Skip any whitespace at the beginning of the next chunk
            start = end;
            while (start < content.length() && Character.isWhitespace(content.charAt(start))) {
                start++;
            }
        }

        context.getLogger().log("Successfully created " + chunks.size() + " chunks from markdown content");
        return chunks;
    }
}
