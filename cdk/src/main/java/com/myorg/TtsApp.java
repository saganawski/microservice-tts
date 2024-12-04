package com.myorg;

import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;

import java.util.UUID;

public class TtsApp {
    public static void main(final String[] args) {
        App app = new App();

        final String uuid = UUID.randomUUID().toString().replace("-", "");

        final S3Stack s3Stack = new S3Stack(app, "S3Stack", StackProps.builder().build(), uuid);

        final String originalFileBucketName = "original-file-bucket" + uuid;
        final String chunkFileBucketName = "chunk-file-bucket" + uuid;
        final String processedFileBucketName = "processed-file-bucket" + uuid;

        final LambdaStack lambdaStack = new LambdaStack(app, "LambdaStack", StackProps.builder().build(),
                originalFileBucketName, chunkFileBucketName, processedFileBucketName);

        new S3EventNotificationStack(app, "S3EventNotificationStack", StackProps.builder().build(),
                s3Stack.getOriginalFileBucket(), s3Stack.getChunkFileBucket(), lambdaStack.getTransformLambda(), lambdaStack.getTtsLambda());

        new NotificationStack(app, "NotificationStack", StackProps.builder().build());

        new ApiStack(app, "ApiStack", StackProps.builder().build(), lambdaStack.getValidationLambda());

        app.synth();
    }
}

