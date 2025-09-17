package com.myorg;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.myorg.tts.TtsConfig;
import com.myorg.tts.TtsProvider;
import com.myorg.tts.TtsProviderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
    private static final int CHUNK_SIZE = 900;

    private final S3Client s3Client;
    private final TtsProvider ttsProvider;
    private final TtsConfig ttsConfig;

    public TtsLambda() {
        this.s3Client = S3Client.builder().build();
        this.ttsProvider = TtsProviderFactory.createProvider();
        this.ttsConfig = TtsProviderFactory.createDefaultConfig();
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

            context.getLogger().log("Processing file: " + objectKey + " from bucket: " + bucketName);

            // Check if this is a metadata.json file - if so, copy it directly to processed bucket
            if (objectKey.endsWith("metadata.json")) {
                context.getLogger().log("Detected metadata.json file, copying directly to processed bucket without TTS conversion");
                copyMetadataToProcessedBucket(bucketName, objectKey, context);
                String result = "Successfully copied metadata.json to processed bucket: " + objectKey;
                context.getLogger().log(result);
                return result;
            }

            // Check if this is a markdown file
            if (!objectKey.endsWith(".md")) {
                context.getLogger().log("Skipping non-markdown file: " + objectKey);
                return "Skipped non-markdown file: " + objectKey;
            }

            // Download markdown file from S3
            String markdownContent = downloadMarkdownFromS3(bucketName, objectKey, context);
            context.getLogger().log("Downloaded markdown content, length: " + markdownContent.length());

            // Chunk the markdown content into 900-character segments
            List<String> chunks = chunkMarkdownContent(markdownContent, context);
            context.getLogger().log("Split markdown into " + chunks.size() + " chunks of max " + CHUNK_SIZE + " characters each");

            // Process each chunk with TTS
            List<String> audioFiles = new ArrayList<>();
            context.getLogger().log("Using TTS provider: " + ttsProvider.getProviderName());

            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                context.getLogger().log("Processing chunk " + (i + 1) + "/" + chunks.size() + " (length: " + chunk.length() + ")");

                // Check remaining time before API call
                context.getLogger().log("Time remaining before TTS API call: " + context.getRemainingTimeInMillis() + "ms");

                // Convert chunk to speech using configured TTS provider
                byte[] audioData = convertTextToSpeech(chunk, context);
                context.getLogger().log("Generated audio data for chunk " + (i + 1) + ", size: " + audioData.length + " bytes");

                // Upload MP3 to processed bucket
                String outputKey = generateChunkOutputKey(objectKey, i);
                uploadAudioToS3(outputKey, audioData, context);
                audioFiles.add(outputKey);
                context.getLogger().log("Successfully uploaded audio file: " + outputKey);
            }
            String result = "Successfully processed " + chunks.size() + " chunks from markdown file using " + ttsProvider.getProviderName() + " provider. Generated audio files: " + String.join(", ", audioFiles);
            context.getLogger().log(result);
            return result;

        } catch (Exception e) {
            context.getLogger().log("Error processing TTS: " + e.getMessage());
            throw new RuntimeException("Failed to process TTS", e);
        }
    }


    private byte[] convertTextToSpeech(String textContent, Context context) throws IOException, InterruptedException {
        context.getLogger().log("Converting text to speech using " + ttsProvider.getProviderName() + " provider");

        try {
            // Use the configured TTS provider
            return ttsProvider.convertTextToSpeech(textContent, ttsConfig);
        } catch (Exception e) {
            context.getLogger().log("TTS conversion error: " + e.getMessage());
            throw e;
        }
    }

    private String generateChunkOutputKey(String inputKey, int chunkIndex) {
        // Remove .md extension and add chunk index and .mp3
        String baseName = inputKey.replaceAll("\\.md$", "");
        return String.format("%s_chunk_%03d.mp3", baseName, chunkIndex + 1);
    }

    private void copyMetadataToProcessedBucket(String sourceBucket, String objectKey, Context context) {
        context.getLogger().log("Copying metadata.json from " + sourceBucket + "/" + objectKey + " to " + PROCESSED_BUCKET_NAME);

        try {
            // Download the metadata file
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(sourceBucket)
                    .key(objectKey)
                    .build();

            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(getObjectRequest);
            byte[] metadataContent = objectBytes.asByteArray();

            // Upload to processed bucket with same key structure
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(PROCESSED_BUCKET_NAME)
                    .key(objectKey)
                    .contentType("application/json")
                    .contentLength((long) metadataContent.length)
                    .build();

            PutObjectResponse putObjectResponse = s3Client.putObject(putObjectRequest,
                    RequestBody.fromInputStream(new ByteArrayInputStream(metadataContent), metadataContent.length));

            context.getLogger().log("Successfully copied metadata.json to processed bucket");
            context.getLogger().log("ETag: " + putObjectResponse.eTag());

        } catch (Exception e) {
            context.getLogger().log("ERROR copying metadata.json: " + e.getMessage());
            throw new RuntimeException("Failed to copy metadata.json to processed bucket", e);
        }
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
