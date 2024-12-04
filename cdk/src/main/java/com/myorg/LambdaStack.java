package com.myorg;

import org.jetbrains.annotations.NotNull;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.constructs.Construct;

import java.util.Map;

public class LambdaStack extends Stack {
    private final Function validationLambda;
    private final Function transformLambda;
    private final Function ttsLambda;

    public LambdaStack(final Construct scope, final String id, final StackProps props, @NotNull String originalFileBucket, @NotNull String chunkFileBucket, @NotNull String processedFileBucket) {
        super(scope, id, props);

        validationLambda = Function.Builder.create(this, "FileValidationLambda")
                .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_21)
                .code(Code.fromAsset("lambdas/file-validation-lambda/target/file-validation-lambda.jar"))
                .handler("com.myorg.FileValidationLambda::handleRequest")
                .environment(Map.of(
                        "CHUNK_BUCKET_NAME", chunkFileBucket,
                        "ORIGINAL_BUCKET_NAME", originalFileBucket
                ))
                .build();

        transformLambda = Function.Builder.create(this, "TransformLambda")
                .runtime(Runtime.JAVA_21)
                .code(Code.fromAsset("lambdas/file-transform-lambda/target/file-transform-lambda.jar"))
                .handler("com.myorg.TransformLambda::handleRequest")
                .environment(Map.of(
                        "ORIGINAL_BUCKET_NAME", originalFileBucket,
                        "CHUNK_BUCKET_NAME", chunkFileBucket
                ))
                .build();


        // TTS lambda may need to switch to a python lambda
        ttsLambda = Function.Builder.create(this, "TTSLambda")
                .runtime(Runtime.JAVA_21)
                .code(Code.fromAsset("lambdas/file-tts-lambda/target/file-tts-lambda.jar"))
                .handler("com.myorg.TtsLambda::handleRequest")
                .environment(Map.of(
                        "CHUNK_BUCKET_NAME", chunkFileBucket,
                        "PROCESSED_BUCKET_NAME", processedFileBucket
                ))
                .build();

    }

    public Function getValidationLambda() {
        return validationLambda;
    }
    public Function getTransformLambda() {
        return transformLambda;
    }
    public Function getTtsLambda() {
        return ttsLambda;
    }
}
