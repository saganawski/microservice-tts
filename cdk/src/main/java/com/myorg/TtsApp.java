package com.myorg;

import com.myorg.stacks.VibeVoiceStack;
import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;

public class TtsApp {
    public static void main(final String[] args) {
        App app = new App();

        // Get AWS account and region from environment
        String account = System.getenv("CDK_DEFAULT_ACCOUNT");
        String region = System.getenv("CDK_DEFAULT_REGION");

        software.amazon.awscdk.Environment env = software.amazon.awscdk.Environment.builder()
            .account(account != null ? account : "272765753210")
            .region(region != null ? region : "us-east-1")
            .build();

        // Add VibeVoice stack ONLY when explicitly requested
        // This prevents accidental EC2 charges
        String deployVibeVoice = System.getenv("DEPLOY_VIBEVOICE");
        String vibeVoiceUrl = null;

        if ("true".equalsIgnoreCase(deployVibeVoice)) {
            VibeVoiceStack vibeVoiceStack = new VibeVoiceStack(
                app,
                "VibeVoiceStack",
                StackProps.builder().env(env).build(),
                null, // markdownBucket not needed - using chapters bucket now
                null  // processedBucket not needed
            );

            // Get the VibeVoice service URL for Lambda configuration
            vibeVoiceUrl = vibeVoiceStack.getServiceUrl();

            // Note: VibeVoice EC2 will be deployed and configured
            // The TTS Lambda will automatically use VibeVoice when this stack is deployed
        }

        // Create FileFlowStack with VibeVoice URL if available
        final FileFlowStack fileFlowStack = new FileFlowStack(app, "FileFlowStack",
            StackProps.builder().env(env).build(), vibeVoiceUrl);

        new ApiStack(app, "ApiStack", StackProps.builder().env(env).build(),
            fileFlowStack.getValidationLambda());

        app.synth();
    }
}

