package com.myorg;

import com.myorg.stacks.VibeVoiceStack;
import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;

public class TtsApp {
    public static void main(final String[] args) {
        App app = new App();

        final FileFlowStack fileFlowStack = new FileFlowStack(app, "FileFlowStack", StackProps.builder().build());

        new ApiStack(app, "ApiStack", StackProps.builder().build(), fileFlowStack.getValidationLambda());

        // Add VibeVoice stack ONLY when explicitly requested
        // This prevents accidental EC2 charges
        String deployVibeVoice = System.getenv("DEPLOY_VIBEVOICE");
        if ("true".equalsIgnoreCase(deployVibeVoice)) {
            VibeVoiceStack vibeVoiceStack = new VibeVoiceStack(
                app,
                "VibeVoiceStack",
                StackProps.builder().build(),
                fileFlowStack.getMarkdownFileBucket(),
                fileFlowStack.getProcessedFileBucket()
            );

            // Note: VibeVoice EC2 will be deployed but STOPPED by default
            // Use './deploy-vibevoice.sh switch-to-vibevoice' to start and use it
            // Use './deploy-vibevoice.sh switch-to-openai' to stop and save money
        }

        app.synth();
    }
}

