package com.myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.EventType;
import software.amazon.awscdk.services.s3.IBucket;
import software.amazon.awscdk.services.s3.notifications.LambdaDestination;
import software.constructs.Construct;

import java.util.Map;

public class LambdaStack extends Stack {
    private final Function validationLambda;

    public LambdaStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        final IBucket originalFileBucket = Bucket.fromBucketName(this, "OriginalFileBucket", "original-file-bucket");
        final IBucket chunkFileBucket = Bucket.fromBucketName(this, "ChunkFileBucket", "chunk-file-bucket");
        final IBucket processedFileBucket = Bucket.fromBucketName(this, "ProcessedFileBucket", "processed-file-bucket");

        validationLambda = Function.Builder.create(this, "FileValidationLambda")
                .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_21)
                .code(Code.fromAsset("lambdas/file-validation-lambda/target/file-validation-lambda.jar"))
                .handler("FileValidationLambda::handleRequest")
                .environment(Map.of(
                        "CHUNK_BUCKET_NAME", chunkFileBucket.getBucketName(),
                        "ORIGINAL_BUCKET_NAME", originalFileBucket.getBucketName()
                ))
                .build();

        final Function transformLambda = Function.Builder.create(this, "TransformLambda")
                .runtime(Runtime.JAVA_21)
                .code(Code.fromAsset("lambdas/file-transform-lambda/target/file-transform-lambda.jar"))
                .handler("TransformLambda::handleRequest")
                .environment(Map.of(
                        "ORIGINAL_BUCKET_NAME", originalFileBucket.getBucketName(),
                        "CHUNK_BUCKET_NAME", chunkFileBucket.getBucketName()
                ))
                .build();


        // TTS lambda may need to switch to a python lambda
        final Function ttsLambda = Function.Builder.create(this, "TTSLambda")
                .runtime(Runtime.JAVA_21)
                .code(Code.fromAsset("lambdas/file-tts-lambda/target/file-tts-lambda.jar"))
                .handler("com.lambdas.TtsLambda::handleRequest")
                .environment(Map.of(
                        "CHUNK_BUCKET_NAME", chunkFileBucket.getBucketName(),
                        "PROCESSED_BUCKET_NAME", processedFileBucket.getBucketName()
                ))
                .build();

        // Event Triggers
        originalFileBucket.addEventNotification(EventType.OBJECT_CREATED,  new LambdaDestination(transformLambda));
        chunkFileBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(ttsLambda));

    }

    public Function getValidationLambda() {
        return validationLambda;
    }
}
