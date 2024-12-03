package com.myorg;

import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;

public class TtsApp {
    public static void main(final String[] args) {
        App app = new App();

        final S3Stack s3Stack = new S3Stack(app, "S3Stack", StackProps.builder().build());

        final LambdaStack lambdaStack = new LambdaStack(app, "LambdaStack", StackProps.builder().build(),
                "original-file-bucket", "chunk-file-bucket", "processed-file-bucket");

        new S3EventNotificationStack(app, "S3EventNotificationStack", StackProps.builder().build(),
                s3Stack.getOriginalFileBucket(), s3Stack.getChunkFileBucket(), lambdaStack.getTransformLambda(), lambdaStack.getTtsLambda());

        new NotificationStack(app, "NotificationStack", StackProps.builder().build());

        new ApiStack(app, "ApiStack", StackProps.builder().build(), lambdaStack.getValidationLambda());

        app.synth();
    }
}

