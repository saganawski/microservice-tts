package com.myorg;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.EventType;
import software.amazon.awscdk.services.s3.notifications.LambdaDestination;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.LambdaSubscription;
import software.constructs.Construct;

import java.util.List;
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

        // ============================================================
        // OLD PIPELINE (Mistral API) - DISABLED
        // To revert: uncomment this block and comment out NEW PIPELINE
        // ============================================================
        /*
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
        */

        // ============================================================
        // NEW PIPELINE (AWS Textract + Bedrock) - ACTIVE
        // To disable: comment out this entire block
        // ============================================================

        // SNS Topic for Textract completion notifications
        final Topic textractCompletionTopic = Topic.Builder.create(this, "TextractCompletionTopic")
                .topicName("textract-completion-topic")
                .build();

        // IAM Role for Textract to publish to SNS
        final Role textractServiceRole = Role.Builder.create(this, "TextractServiceRole")
                .assumedBy(new ServicePrincipal("textract.amazonaws.com"))
                .build();

        textractServiceRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("sns:Publish"))
                .resources(List.of(textractCompletionTopic.getTopicArn()))
                .build());

        // Textract Extractor Lambda (initiates async Textract jobs)
        final Function textractExtractorLambda = Function.Builder.create(this, "TextractExtractorLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/textract-extractor-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.of(
                        "ORIGINAL_BUCKET_NAME", originalFileBucket.getBucketName(),
                        "SNS_TOPIC_ARN", textractCompletionTopic.getTopicArn(),
                        "TEXTRACT_ROLE_ARN", textractServiceRole.getRoleArn()))
                .timeout(Duration.minutes(5))
                .memorySize(512)
                .build();

        // Grant permissions for Textract Extractor
        originalFileBucket.grantRead(textractExtractorLambda);
        originalFileBucket.grantPut(textractExtractorLambda);  // For metadata storage
        textractExtractorLambda.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("textract:StartDocumentTextDetection"))
                .resources(List.of("*"))
                .build());
        textractExtractorLambda.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("iam:PassRole"))
                .resources(List.of(textractServiceRole.getRoleArn()))
                .build());

        // Textract Processor Lambda (retrieves results and creates markdown)
        final Function textractProcessorLambda = Function.Builder.create(this, "TextractProcessorLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/textract-processor-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.of(
                        "MARKDOWN_BUCKET_NAME", markdownFileBucket.getBucketName()))
                .timeout(Duration.minutes(10))
                .memorySize(1024)
                .build();

        // Grant permissions for Textract Processor
        markdownFileBucket.grantPut(textractProcessorLambda);
        textractProcessorLambda.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("textract:GetDocumentTextDetection"))
                .resources(List.of("*"))
                .build());

        // Subscribe Textract Processor to SNS topic
        textractCompletionTopic.addSubscription(new LambdaSubscription(textractProcessorLambda));

        // Bedrock Chapter Lambda (intelligent chapter detection)
        final Function bedrockChapterLambda = Function.Builder.create(this, "BedrockChapterLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/bedrock-chapter-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.of(
                        "MARKDOWN_BUCKET_NAME", markdownFileBucket.getBucketName(),
                        "CHAPTERS_BUCKET_NAME", chaptersBucket.getBucketName(),
                        "BEDROCK_MODEL_ID", "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
                        "BEDROCK_REGION", "us-east-1"))
                .timeout(Duration.minutes(15))  // Large books need time
                .memorySize(1024)
                .build();

        // Grant permissions for Bedrock Chapter Lambda
        markdownFileBucket.grantRead(bedrockChapterLambda);
        chaptersBucket.grantPut(bedrockChapterLambda);  // Multiple chapter uploads
        bedrockChapterLambda.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("bedrock:InvokeModel"))
                .resources(List.of(
                        "arn:aws:bedrock:us-east-1:272765753210:inference-profile/us.anthropic.claude-sonnet-4-5-20250929-v1:0",
                        "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0",
                        "arn:aws:bedrock:us-east-2::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0",
                        "arn:aws:bedrock:us-west-2::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0"))
                .build());

        // TTS lambda with configurable provider support
        Map<String, String> ttsEnvironment = new java.util.HashMap<>();
        ttsEnvironment.put("MARKDOWN_BUCKET_NAME", chaptersBucket.getBucketName());
        ttsEnvironment.put("PROCESSED_BUCKET_NAME", processedFileBucket.getBucketName());

        // TTS Provider configuration
        ttsEnvironment.put("TTS_PROVIDER", System.getenv("TTS_PROVIDER") != null ? System.getenv("TTS_PROVIDER") : "OPENAI");

        // OpenAI configuration (when using OpenAI provider)
        if (System.getenv("OPENAI_API_KEY") != null) {
            ttsEnvironment.put("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"));
        }

        // Self-hosted TTS configuration (when using self-hosted provider)
        if (System.getenv("TTS_ENDPOINT_URL") != null) {
            ttsEnvironment.put("TTS_ENDPOINT_URL", System.getenv("TTS_ENDPOINT_URL"));
        }
        if (System.getenv("TTS_API_KEY") != null) {
            ttsEnvironment.put("TTS_API_KEY", System.getenv("TTS_API_KEY"));
        }
        if (System.getenv("TTS_AUTH_HEADER") != null) {
            ttsEnvironment.put("TTS_AUTH_HEADER", System.getenv("TTS_AUTH_HEADER"));
        }

        // Generic TTS configuration
        if (System.getenv("TTS_VOICE") != null) {
            ttsEnvironment.put("TTS_VOICE", System.getenv("TTS_VOICE"));
        }
        if (System.getenv("TTS_MODEL") != null) {
            ttsEnvironment.put("TTS_MODEL", System.getenv("TTS_MODEL"));
        }
        if (System.getenv("TTS_SPEED") != null) {
            ttsEnvironment.put("TTS_SPEED", System.getenv("TTS_SPEED"));
        }
        if (System.getenv("TTS_LANGUAGE") != null) {
            ttsEnvironment.put("TTS_LANGUAGE", System.getenv("TTS_LANGUAGE"));
        }
        if (System.getenv("TTS_INSTRUCTIONS") != null) {
            ttsEnvironment.put("TTS_INSTRUCTIONS", System.getenv("TTS_INSTRUCTIONS"));
        }
        if (System.getenv("TTS_RESPONSE_FORMAT") != null) {
            ttsEnvironment.put("TTS_RESPONSE_FORMAT", System.getenv("TTS_RESPONSE_FORMAT"));
        }

        // OLD Java TTS Lambda (commented out - replaced by Gemini TTS)
        // final Function ttsLambda = Function.Builder.create(this, "TTSLambda")
        //         .runtime(Runtime.JAVA_21)
        //         .code(Code.fromAsset("lambdas/file-tts-lambda/target/file-tts-lambda.jar"))
        //         .handler("com.myorg.TtsLambda::handleRequest")
        //         .environment(ttsEnvironment)
        //         .timeout(Duration.minutes(15))
        //         .memorySize(1024)
        //         .build();

        // // Grant permissions to the lambda functions to access the S3 buckets
        // chaptersBucket.grantRead(ttsLambda);
        // processedFileBucket.grantPut(ttsLambda);

        // Gemini TTS Lambda (Python-based, replaces Java TTS Lambda)
        final Function geminiTtsLambda = Function.Builder.create(this, "GeminiTTSLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/gemini-tts-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.of(
                        "GEMINI_API_KEY", System.getenv("GEMINI_API_KEY"),
                        "GEMINI_TTS_MODEL", "gemini-2.5-pro-preview-tts",  // Premium model
                        "GEMINI_TTS_VOICE", "Charon"))
                .timeout(Duration.minutes(15))  // Increased for Pro model
                .memorySize(1024)  // Increased for better performance
                .build();

        // Grant S3 permissions for Gemini TTS Lambda
        chaptersBucket.grantRead(geminiTtsLambda);
        processedFileBucket.grantPut(geminiTtsLambda);

        // ============================================================
        // S3 EVENT NOTIFICATIONS
        // ============================================================

        // OLD PIPELINE EVENT NOTIFICATIONS - DISABLED
        // originalFileBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(transformLambda));
        // markdownFileBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(chapterSplitterLambda));

        // NEW PIPELINE EVENT NOTIFICATIONS - ACTIVE
        originalFileBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(textractExtractorLambda));
        markdownFileBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(bedrockChapterLambda));

        // TTS trigger remains unchanged (works with both pipelines)
        chaptersBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(geminiTtsLambda));
    }

    public Function getValidationLambda() {
        return validationLambda;
    }
}
