package com.myorg;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.EventType;
import software.amazon.awscdk.services.s3.notifications.LambdaDestination;
import software.constructs.Construct;

import java.util.Map;


public class FileFlowStack extends Stack {
    private final Function validationLambda;
    private final Bucket markdownFileBucket;
    private final Bucket processedFileBucket;

    public FileFlowStack(final Construct scope, final String id, final StackProps props) {
        this(scope, id, props, null);
    }

    public FileFlowStack(final Construct scope, final String id, final StackProps props, final String vibeVoiceUrl) {

        super(scope, id, props);

        final String accountNumber = "272765753210";

        final Bucket originalFileBucket = Bucket.Builder.create(this, "OriginalFileBucket")
                .bucketName("original-file-bucket" + accountNumber)
                .versioned(false)
                .build();

        markdownFileBucket = Bucket.Builder.create(this, "MarkdownFileBucket")
                .bucketName("markdown-file-bucket" + accountNumber)
                .versioned(false)
                .build();

        final Bucket chaptersBucket = Bucket.Builder.create(this, "ChaptersBucket")
                .bucketName("chapters-bucket" + accountNumber)
                .versioned(false)
                .build();

        processedFileBucket = Bucket.Builder.create(this, "ProcessedFileBucket")
                .bucketName("processed-file-bucket" + accountNumber)
                .versioned(false)
                .build();

        validationLambda = Function.Builder.create(this, "FileValidationLambda")
                .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_21)
                .code(Code.fromAsset("../lambdas/file-validation-lambda/target/file-validation-lambda.jar"))
                .handler("com.myorg.FileValidationLambda::handleRequest")
                .environment(Map.of(
                        "ORIGINAL_BUCKET_NAME", originalFileBucket.getBucketName()))
                .timeout(Duration.minutes(15))
                .memorySize(1024)
                .build();

        // Grant permissions to the lambda functions to access the S3 buckets
        originalFileBucket.grantPut(validationLambda);

        // Configure TTS provider for TransformLambda
        Map<String, String> transformEnvironment = new java.util.HashMap<>();
        transformEnvironment.put("ORIGINAL_BUCKET_NAME", originalFileBucket.getBucketName());
        transformEnvironment.put("MARKDOWN_BUCKET_NAME", markdownFileBucket.getBucketName());
        transformEnvironment.put("CHAPTERS_BUCKET_NAME", chaptersBucket.getBucketName());
        transformEnvironment.put("MISTRAL_API_KEY", System.getenv("MISTRAL_API_KEY"));

        // Add TTS provider configuration for routing
        transformEnvironment.put("TTS_PROVIDER", System.getenv("TTS_PROVIDER") != null ? System.getenv("TTS_PROVIDER") : "OPENAI");
        transformEnvironment.put("OPENAI_MAX_CHUNK", "4096");
        transformEnvironment.put("VIBEVOICE_MAX_CHUNK", "192000"); // 75% of 256k

        final Function transformLambda = Function.Builder.create(this, "TransformLambda")
                .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_21)
                .code(Code.fromAsset("../lambdas/file-transform-lambda/target/file-transform-lambda.jar"))
                .handler("com.myorg.TransformLambda::handleRequest")
                .environment(transformEnvironment)
                .timeout(Duration.minutes(5))
                .build();

        // Grant permissions to the lambda functions to access the S3 buckets
        originalFileBucket.grantRead(transformLambda);
        markdownFileBucket.grantPut(transformLambda);
        chaptersBucket.grantPut(transformLambda); // For direct routing when using VibeVoice

        // Chapter Splitter Lambda with TTS provider configuration
        Map<String, String> chapterSplitterEnvironment = new java.util.HashMap<>();
        chapterSplitterEnvironment.put("MARKDOWN_BUCKET_NAME", markdownFileBucket.getBucketName());
        chapterSplitterEnvironment.put("CHAPTERS_BUCKET_NAME", chaptersBucket.getBucketName());
        chapterSplitterEnvironment.put("TTS_PROVIDER", System.getenv("TTS_PROVIDER") != null ? System.getenv("TTS_PROVIDER") : "OPENAI");
        chapterSplitterEnvironment.put("OPENAI_MAX_CHUNK", "4096");
        chapterSplitterEnvironment.put("VIBEVOICE_MAX_CHUNK", "192000"); // 75% of 256k

        final Function chapterSplitterLambda = Function.Builder.create(this, "ChapterSplitterLambda")
                .runtime(Runtime.JAVA_21)
                .code(Code.fromAsset("../lambdas/chapter-splitter-lambda/target/chapter-splitter-lambda.jar"))
                .handler("com.myorg.ChapterSplitterLambda::handleRequest")
                .environment(chapterSplitterEnvironment)
                .timeout(Duration.minutes(5))
                .memorySize(512)
                .build();

        // Grant permissions for ChapterSplitter Lambda
        markdownFileBucket.grantRead(chapterSplitterLambda);
        chaptersBucket.grantPut(chapterSplitterLambda);

        // TTS lambda with configurable provider support
        Map<String, String> ttsEnvironment = new java.util.HashMap<>();
        ttsEnvironment.put("MARKDOWN_BUCKET_NAME", chaptersBucket.getBucketName());
        ttsEnvironment.put("PROCESSED_BUCKET_NAME", processedFileBucket.getBucketName());

        // TTS Provider configuration - prioritize VibeVoice if URL is provided
        String ttsProvider = "OPENAI"; // Default
        if (vibeVoiceUrl != null && !vibeVoiceUrl.isEmpty()) {
            ttsProvider = "VIBEVOICE";
            ttsEnvironment.put("VIBEVOICE_ENDPOINT_URL", vibeVoiceUrl);
        } else if (System.getenv("TTS_PROVIDER") != null) {
            ttsProvider = System.getenv("TTS_PROVIDER");
        }

        ttsEnvironment.put("TTS_PROVIDER", ttsProvider);
        ttsEnvironment.put("OPENAI_MAX_CHUNK", "4096");
        ttsEnvironment.put("VIBEVOICE_MAX_CHUNK", "192000"); // 75% of 256k

        // OpenAI configuration (when using OpenAI provider)
        if (System.getenv("OPENAI_API_KEY") != null) {
            ttsEnvironment.put("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"));
        }

        // Self-hosted TTS configuration (when using self-hosted provider)
        if (System.getenv("TTS_ENDPOINT_URL") != null) {
            ttsEnvironment.put("TTS_ENDPOINT_URL", System.getenv("TTS_ENDPOINT_URL"));
        }
        if (System.getenv("TTS_API_KEY") != null) {
            ttsEnvironment.put("TTS_API_KEY", System.getenv("TTS_API_KEY"));
        }
        if (System.getenv("TTS_AUTH_HEADER") != null) {
            ttsEnvironment.put("TTS_AUTH_HEADER", System.getenv("TTS_AUTH_HEADER"));
        }

        // Generic TTS configuration
        if (System.getenv("TTS_VOICE") != null) {
            ttsEnvironment.put("TTS_VOICE", System.getenv("TTS_VOICE"));
        }
        if (System.getenv("TTS_MODEL") != null) {
            ttsEnvironment.put("TTS_MODEL", System.getenv("TTS_MODEL"));
        }
        if (System.getenv("TTS_SPEED") != null) {
            ttsEnvironment.put("TTS_SPEED", System.getenv("TTS_SPEED"));
        }
        if (System.getenv("TTS_LANGUAGE") != null) {
            ttsEnvironment.put("TTS_LANGUAGE", System.getenv("TTS_LANGUAGE"));
        }
        if (System.getenv("TTS_INSTRUCTIONS") != null) {
            ttsEnvironment.put("TTS_INSTRUCTIONS", System.getenv("TTS_INSTRUCTIONS"));
        }
        if (System.getenv("TTS_RESPONSE_FORMAT") != null) {
            ttsEnvironment.put("TTS_RESPONSE_FORMAT", System.getenv("TTS_RESPONSE_FORMAT"));
        }

        final Function ttsLambda = Function.Builder.create(this, "TTSLambda")
                .runtime(Runtime.JAVA_21)
                .code(Code.fromAsset("../lambdas/file-tts-lambda/target/file-tts-lambda.jar"))
                .handler("com.myorg.TtsLambda::handleRequest")
                .environment(ttsEnvironment)
                .timeout(Duration.minutes(15))
                .memorySize(1024)
                .build();

        // Grant permissions to the lambda functions to access the S3 buckets
        chaptersBucket.grantRead(ttsLambda);
        processedFileBucket.grantPut(ttsLambda);

        // add the S3 event notification
        originalFileBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(transformLambda));
        markdownFileBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(chapterSplitterLambda));
        chaptersBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(ttsLambda));
    }

    public Function getValidationLambda() {
        return validationLambda;
    }

    public Bucket getMarkdownFileBucket() {
        return markdownFileBucket;
    }

    public Bucket getProcessedFileBucket() {
        return processedFileBucket;
    }
}
