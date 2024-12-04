package com.myorg;

import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;

public class TtsApp {
    public static void main(final String[] args) {
        App app = new App();

        final FileFlowStack fileFlowStack = new FileFlowStack(app, "FileFlowStack", StackProps.builder().build());

        new ApiStack(app, "ApiStack", StackProps.builder().build(), fileFlowStack.getValidationLambda());

        app.synth();
    }
}

