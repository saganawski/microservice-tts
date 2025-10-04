package com.myorg;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChapterSplitterLambda implements RequestHandler<Map<String, Object>, String> {
    private static final String MARKDOWN_BUCKET_NAME = System.getenv("MARKDOWN_BUCKET_NAME");
    private static final String CHAPTERS_BUCKET_NAME = System.getenv("CHAPTERS_BUCKET_NAME");
    private static final String TTS_PROVIDER = System.getenv("TTS_PROVIDER") != null ? System.getenv("TTS_PROVIDER") : "OPENAI";
    private static final int OPENAI_MAX_CHUNK = Integer.parseInt(System.getenv("OPENAI_MAX_CHUNK") != null ? System.getenv("OPENAI_MAX_CHUNK") : "4096");
    private static final int VIBEVOICE_MAX_CHUNK = Integer.parseInt(System.getenv("VIBEVOICE_MAX_CHUNK") != null ? System.getenv("VIBEVOICE_MAX_CHUNK") : "192000");
    private static final int DEFAULT_WORDS_PER_CHAPTER = 5000;
    private static final Pattern H1_PATTERN = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);

    private final S3Client s3Client = S3Client.builder().region(Region.US_EAST_1).build();
    private final ObjectMapper objectMapper;

    public ChapterSplitterLambda() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        context.getLogger().log("ChapterSplitter Lambda initiated");
        context.getLogger().log("Received event: " + event);

        if (MARKDOWN_BUCKET_NAME == null || CHAPTERS_BUCKET_NAME == null) {
            context.getLogger().log("ERROR: Missing required environment variables");
            return "Error: Missing bucket configuration";
        }

        // Extract file name from S3 event
        final List<Map<String, Object>> records = (List<Map<String, Object>>) event.get("Records");
        final String markdownFileName = records.stream()
            .map(record -> (Map<String, Object>) record.get("s3"))
            .map(s3 -> (Map<String, Object>) s3.get("object"))
            .map(object -> (String) object.get("key"))
            .findFirst()
            .orElse(null);

        if (markdownFileName == null) {
            context.getLogger().log("ERROR: No file name found in event");
            return "Error: No file name found";
        }

        context.getLogger().log("Processing markdown file: " + markdownFileName);

        try {
            // Download markdown content from S3
            String markdownContent = downloadMarkdownFromS3(markdownFileName, context);

            // Split into chapters based on TTS provider
            List<Chapter> chapters = splitIntoChapters(markdownContent, context);
            context.getLogger().log("Split document into " + chapters.size() + " chapters");

            // Generate base name for output files
            String baseName = markdownFileName.replace(".md", "");

            // Create metadata
            DocumentMetadata metadata = createMetadata(baseName, markdownFileName, chapters);

            // Upload metadata first
            uploadMetadata(baseName, metadata, context);

            // Upload each chapter
            int successCount = 0;
            for (Chapter chapter : chapters) {
                try {
                    uploadChapter(baseName, chapter, context);
                    successCount++;
                    context.getLogger().log("Successfully uploaded chapter " + chapter.index + ": " + chapter.title);
                } catch (Exception e) {
                    context.getLogger().log("ERROR uploading chapter " + chapter.index + ": " + e.getMessage());
                    // Continue with other chapters
                }
            }

            context.getLogger().log("Chapter splitting completed. Uploaded " + successCount + "/" + chapters.size() + " chapters");
            return "Successfully processed " + successCount + " chapters from " + markdownFileName;

        } catch (Exception e) {
            context.getLogger().log("ERROR processing file: " + e.getMessage());
            e.printStackTrace();
            return "Error: " + e.getMessage();
        }
    }

    private String downloadMarkdownFromS3(String fileName, Context context) throws IOException {
        context.getLogger().log("Downloading markdown file from S3: " + fileName);

        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(MARKDOWN_BUCKET_NAME)
            .key(fileName)
            .build();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        GetObjectResponse response = s3Client.getObject(request, ResponseTransformer.toOutputStream(outputStream));

        if (!response.sdkHttpResponse().isSuccessful()) {
            throw new IOException("Failed to download file from S3");
        }

        String content = outputStream.toString(StandardCharsets.UTF_8);
        context.getLogger().log("Downloaded markdown file, size: " + content.length() + " characters");
        return content;
    }

    private List<Chapter> splitIntoChapters(String markdownContent, Context context) {
        // Determine chunk size based on TTS provider
        int maxChunkSize = TTS_PROVIDER.equalsIgnoreCase("VIBEVOICE") ? VIBEVOICE_MAX_CHUNK : OPENAI_MAX_CHUNK;
        context.getLogger().log("Using TTS provider: " + TTS_PROVIDER + " with max chunk size: " + maxChunkSize + " characters");

        // Use character-based chunking for TTS processing
        return splitByCharacterCount(markdownContent, maxChunkSize, context);
    }

    private List<Chapter> splitByCharacterCount(String content, int maxCharacters, Context context) {
        List<Chapter> chapters = new ArrayList<>();
        String[] sentences = content.split("(?<=[.!?])\\s+");

        StringBuilder currentChapter = new StringBuilder();
        int chapterIndex = 1;

        for (String sentence : sentences) {
            // Check if adding this sentence would exceed the max character limit
            if (currentChapter.length() + sentence.length() + 1 > maxCharacters && currentChapter.length() > 0) {
                // Save current chapter
                String chapterContent = currentChapter.toString().trim();
                chapters.add(new Chapter(
                    chapterIndex,
                    "Part " + chapterIndex,
                    generateSimpleChapterFileName(chapterIndex),
                    chapterContent,
                    countWords(chapterContent)
                ));
                context.getLogger().log("Created chapter " + chapterIndex + " with " + chapterContent.length() + " characters");

                // Start new chapter
                currentChapter = new StringBuilder();
                chapterIndex++;
            }

            currentChapter.append(sentence).append(" ");
        }

        // Add remaining content as final chapter
        if (currentChapter.length() > 0) {
            String chapterContent = currentChapter.toString().trim();
            chapters.add(new Chapter(
                chapterIndex,
                "Part " + chapterIndex,
                generateSimpleChapterFileName(chapterIndex),
                chapterContent,
                countWords(chapterContent)
            ));
            context.getLogger().log("Created final chapter " + chapterIndex + " with " + chapterContent.length() + " characters");
        }

        context.getLogger().log("Split content into " + chapters.size() + " chapters by character count (max " + maxCharacters + " chars per chapter)");
        return chapters;
    }

    private int countWords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    private String sanitizeTitle(String title) {
        // Remove special characters and limit length
        String sanitized = title.replaceAll("[^a-zA-Z0-9\\s-]", "")
            .replaceAll("\\s+", "_")
            .toLowerCase();

        if (sanitized.length() > 50) {
            sanitized = sanitized.substring(0, 50);
        }

        return sanitized;
    }

    private String generateSimpleChapterFileName(int index) {
        String paddedIndex = String.format("%03d", index);
        return paddedIndex + ".md";
    }

    private DocumentMetadata createMetadata(String baseName, String sourceFile, List<Chapter> chapters) {
        DocumentMetadata metadata = new DocumentMetadata();
        metadata.sourceFile = sourceFile;
        metadata.totalChapters = chapters.size();
        metadata.processedAt = Instant.now().toString();
        metadata.chapters = new ArrayList<>();

        for (Chapter chapter : chapters) {
            ChapterMetadata chapterMeta = new ChapterMetadata();
            chapterMeta.index = chapter.index;
            chapterMeta.fileName = chapter.fileName;
            chapterMeta.title = chapter.title;
            chapterMeta.wordCount = chapter.wordCount;
            chapterMeta.status = "pending";
            metadata.chapters.add(chapterMeta);
        }

        return metadata;
    }

    private void uploadMetadata(String baseName, DocumentMetadata metadata, Context context) throws Exception {
        String metadataKey = baseName + "/metadata.json";
        String metadataJson = objectMapper.writeValueAsString(metadata);

        context.getLogger().log("Uploading metadata to: " + metadataKey);

        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(CHAPTERS_BUCKET_NAME)
            .key(metadataKey)
            .contentType("application/json")
            .build();

        s3Client.putObject(request, RequestBody.fromString(metadataJson));
        context.getLogger().log("Metadata uploaded successfully");
    }

    private void uploadChapter(String baseName, Chapter chapter, Context context) throws Exception {
        String chapterKey = baseName + "/" + chapter.fileName;

        context.getLogger().log("Uploading chapter to: " + chapterKey);

        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(CHAPTERS_BUCKET_NAME)
            .key(chapterKey)
            .contentType("text/markdown")
            .metadata(Map.of(
                "chapter-index", String.valueOf(chapter.index),
                "chapter-title", chapter.title,
                "word-count", String.valueOf(chapter.wordCount)
            ))
            .build();

        s3Client.putObject(request, RequestBody.fromString(chapter.content));
    }

    // Inner classes for data structures
    private static class Chapter {
        final int index;
        final String title;
        final String fileName;
        final String content;
        final int wordCount;

        Chapter(int index, String title, String fileName, String content, int wordCount) {
            this.index = index;
            this.title = title;
            this.fileName = fileName;
            this.content = content;
            this.wordCount = wordCount;
        }
    }

    private static class DocumentMetadata {
        public String sourceFile;
        public int totalChapters;
        public String processedAt;
        public List<ChapterMetadata> chapters;
    }

    private static class ChapterMetadata {
        public int index;
        public String fileName;
        public String title;
        public int wordCount;
        public String status;
    }
}