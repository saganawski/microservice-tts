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

    public FileFlowStack(final Construct scope, final String id, final StackProps props) {

        super(scope, id, props);

        final String accountNumber = "272765753210";

        final Bucket originalFileBucket = Bucket.Builder.create(this, "OriginalFileBucket")
                .bucketName("original-file-bucket" + accountNumber)
                .versioned(false)
                .build();

        final Bucket markdownFileBucket = Bucket.Builder.create(this, "MarkdownFileBucket")
                .bucketName("markdown-file-bucket" + accountNumber)
                .versioned(false)
                .build();

        final Bucket chaptersBucket = Bucket.Builder.create(this, "ChaptersBucket")
                .bucketName("chapters-bucket" + accountNumber)
                .versioned(false)
                .build();

        final Bucket processedFileBucket = Bucket.Builder.create(this, "ProcessedFileBucket")
                .bucketName("processed-file-bucket" + accountNumber)
                .versioned(false)
                .build();

        validationLambda = Function.Builder.create(this, "FileValidationLambda")
                .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_21)
                .code(Code.fromAsset("lambdas/file-validation-lambda/target/file-validation-lambda.jar"))
                .handler("com.myorg.FileValidationLambda::handleRequest")
                .environment(Map.of(
                        "ORIGINAL_BUCKET_NAME", originalFileBucket.getBucketName()))
                .timeout(Duration.minutes(15))
                .memorySize(1024)
                .build();

        // Grant permissions to the lambda functions to access the S3 buckets
        originalFileBucket.grantPut(validationLambda);

        final Function transformLambda = Function.Builder.create(this, "TransformLambda")
                .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_21)
                .code(Code.fromAsset("lambdas/file-transform-lambda/target/file-transform-lambda.jar"))
                .handler("com.myorg.TransformLambda::handleRequest")
                .environment(Map.of(
                        "ORIGINAL_BUCKET_NAME", originalFileBucket.getBucketName(),
                        "MARKDOWN_BUCKET_NAME", markdownFileBucket.getBucketName(),
                        "MISTRAL_API_KEY", System.getenv("MISTRAL_API_KEY")))
                .timeout(Duration.minutes(5))
                .build();

        // Grant permissions to the lambda functions to access the S3 buckets
        originalFileBucket.grantRead(transformLambda);
        markdownFileBucket.grantPut(transformLambda);

        // Chapter Splitter Lambda
        final Function chapterSplitterLambda = Function.Builder.create(this, "ChapterSplitterLambda")
                .runtime(Runtime.JAVA_21)
                .code(Code.fromAsset("lambdas/chapter-splitter-lambda/target/chapter-splitter-lambda.jar"))
                .handler("com.myorg.ChapterSplitterLambda::handleRequest")
                .environment(Map.of(
                        "MARKDOWN_BUCKET_NAME", markdownFileBucket.getBucketName(),
                        "CHAPTERS_BUCKET_NAME", chaptersBucket.getBucketName()))
                .timeout(Duration.minutes(5))
                .memorySize(512)
                .build();

        // Grant permissions for ChapterSplitter Lambda
        markdownFileBucket.grantRead(chapterSplitterLambda);
        chaptersBucket.grantPut(chapterSplitterLambda);

        // TTS lambda may need to switch to a python lambda
        final Function ttsLambda = Function.Builder.create(this, "TTSLambda")
                .runtime(Runtime.JAVA_21)
                .code(Code.fromAsset("lambdas/file-tts-lambda/target/file-tts-lambda.jar"))
                .handler("com.myorg.TtsLambda::handleRequest")
                .environment(Map.of(
                        "CHAPTERS_BUCKET_NAME", chaptersBucket.getBucketName(),
                        "PROCESSED_BUCKET_NAME", processedFileBucket.getBucketName(),
                        "OPENAI_API_KEY", System.getenv("OPENAI_API_KEY")))
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
}
