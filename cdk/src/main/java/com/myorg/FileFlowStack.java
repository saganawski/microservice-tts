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
        //TODO: bucket names

        final Bucket originalFileBucket = Bucket.Builder.create(this, "OriginalFileBucket")
                .bucketName("original-file-bucket" + accountNumber)
                .versioned(false)
                .build();

        final Bucket chunkFileBucket = Bucket.Builder.create(this, "ChunkFileBucket")
                .bucketName("chunk-file-bucket" + accountNumber)
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
                        "ORIGINAL_BUCKET_NAME", originalFileBucket.getBucketName()
                ))
                .timeout(Duration.minutes(5))
                .build();

        // Grant permissions to the lambda functions to access the S3 buckets
        originalFileBucket.grantPut(validationLambda);

        final Function transformLambda = Function.Builder.create(this, "TransformLambda")
                .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_21)
                .code(Code.fromAsset("lambdas/file-transform-lambda/target/file-transform-lambda.jar"))
                .handler("com.myorg.TransformLambda::handleRequest")
                .environment(Map.of(
                        "ORIGINAL_BUCKET_NAME", originalFileBucket.getBucketName(),
                        "CHUNK_BUCKET_NAME", chunkFileBucket.getBucketName()
                ))
                .timeout(Duration.minutes(5))
                .build();

        // Grant permissions to the lambda functions to access the S3 buckets
        originalFileBucket.grantRead(transformLambda);
        chunkFileBucket.grantPut(transformLambda);

        // TTS lambda may need to switch to a python lambda
        final Function ttsLambda = Function.Builder.create(this, "TTSLambda")
                .runtime(Runtime.JAVA_21)
                .code(Code.fromAsset("lambdas/file-tts-lambda/target/file-tts-lambda.jar"))
                .handler("com.myorg.TtsLambda::handleRequest")
                .environment(Map.of(
                        "CHUNK_BUCKET_NAME", chunkFileBucket.getBucketName(),
                        "PROCESSED_BUCKET_NAME", processedFileBucket.getBucketName()
                ))
                .timeout(Duration.minutes(5))
                .build();

        // Grant permissions to the lambda functions to access the S3 buckets
        chunkFileBucket.grantRead(ttsLambda);
        processedFileBucket.grantPut(ttsLambda);

        //add the S3 event notification
        originalFileBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(transformLambda));
        chunkFileBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(ttsLambda));
    }
    public Function getValidationLambda() {
        return validationLambda;
    }
}
