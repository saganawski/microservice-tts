package com.myorg;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

import java.util.Arrays;

public class TtsApp {
    public static void main(final String[] args) {
        App app = new App();

        new S3Stack(app, "S3Stack", StackProps.builder().build());
        final LambdaStack lambdaStack = new LambdaStack(app, "LambdaStack", StackProps.builder().build());
        new NotificationStack(app, "NotificationStack", StackProps.builder().build());
        new ApiStack(app, "ApiStack", StackProps.builder().build(), lambdaStack.getValidationLambda());

        app.synth();
    }
}

