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
import software.amazon.awscdk.services.lambda.eventsources.SqsEventSource;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.EventType;
import software.amazon.awscdk.services.s3.NotificationKeyFilter;
import software.amazon.awscdk.services.s3.notifications.LambdaDestination;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.LambdaSubscription;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.DeduplicationScope;
import software.amazon.awscdk.services.sqs.FifoThroughputLimit;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;


public class FileFlowStack extends Stack {
    private final Function validationLambda;
    private final Function presignedUrlLambda;

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

        // Pre-signed URL Lambda for large file uploads
        presignedUrlLambda = Function.Builder.create(this, "PresignedUrlLambda")
                .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_21)
                .code(Code.fromAsset("lambdas/presigned-url-lambda/target/presigned-url-lambda.jar"))
                .handler("com.myorg.PresignedUrlLambda::handleRequest")
                .environment(Map.of(
                        "ORIGINAL_BUCKET_NAME", originalFileBucket.getBucketName()))
                .timeout(Duration.seconds(30))
                .memorySize(512)
                .build();

        // Grant permissions for pre-signed URL Lambda
        originalFileBucket.grantPut(presignedUrlLambda);
        originalFileBucket.grantPutAcl(presignedUrlLambda);

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
        // TEXTRACT PIPELINE - DISABLED (replaced by Gemini pipeline)
        // This pipeline used AWS Textract + Bedrock for processing
        // Kept for reference but no longer deployed
        // ============================================================
        /*
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
        */

        // ============================================================
        // OLD TTS CONFIGURATIONS - DISABLED
        // These were used with the Textract pipeline
        // ============================================================
        /*
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
        */

        // ============================================================
        // S3 EVENT NOTIFICATIONS
        // ============================================================

        // OLD PIPELINE EVENT NOTIFICATIONS - DISABLED
        // originalFileBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(transformLambda));
        // markdownFileBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(chapterSplitterLambda));

        // TEXTRACT PIPELINE EVENT NOTIFICATIONS - DISABLED (replaced by Gemini pipeline)
        // originalFileBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(textractExtractorLambda));
        // markdownFileBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(bedrockChapterLambda));
        // chaptersBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(geminiTtsLambda));

        // ============================================================
        // GEMINI FLOW - Full Gemini Vision + TTS Pipeline
        // Uses Gemini for PDF analysis, text extraction, and TTS
        // ============================================================

        // Create bucket for Gemini flow uploads (separate from original bucket)
        final Bucket geminiUploadBucket = Bucket.Builder.create(this, "GeminiUploadBucket")
                .bucketName("gemini-upload-bucket" + accountNumber)
                .versioned(false)
                .build();

        // Create text bucket for extracted text storage (optional, for debugging/caching)
        final Bucket geminiTextBucket = Bucket.Builder.create(this, "GeminiTextBucket")
                .bucketName("gemini-text-bucket" + accountNumber)
                .versioned(false)
                .build();

        // ============================================================
        // CHUNKED ARCHITECTURE - NEW INFRASTRUCTURE
        // ============================================================

        // Create bucket for text chunks (temporary storage)
        final Bucket textChunksBucket = Bucket.Builder.create(this, "TextChunksBucket")
                .bucketName("text-chunks-bucket" + accountNumber)
                .versioned(false)
                .removalPolicy(software.amazon.awscdk.RemovalPolicy.DESTROY)
                .autoDeleteObjects(true)
                .lifecycleRules(List.of(software.amazon.awscdk.services.s3.LifecycleRule.builder()
                        .expiration(Duration.days(7))  // Clean up after 7 days
                        .build()))
                .build();

        // Create bucket for audio chunks (temporary storage)
        final Bucket audioChunksBucket = Bucket.Builder.create(this, "AudioChunksBucket")
                .bucketName("audio-chunks-bucket" + accountNumber)
                .versioned(false)
                .lifecycleRules(List.of(software.amazon.awscdk.services.s3.LifecycleRule.builder()
                        .expiration(Duration.days(7))  // Clean up after 7 days
                        .build()))
                .build();

        // Create DynamoDB table for tracking chunk completion
        final Table chunkTrackingTable = Table.Builder.create(this, "AudioChunkTracking")
                .tableName("AudioChunkTracking")
                .partitionKey(Attribute.builder()
                        .name("chapter_key")
                        .type(AttributeType.STRING)
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .timeToLiveAttribute("ttl")
                .build();

        // SQS FIFO Queue for TTS processing
        final Queue ttsQueue = Queue.Builder.create(this, "TTSProcessingQueue")
                .queueName("tts-processing-queue.fifo")
                .fifo(true)
                .contentBasedDeduplication(true)
                .deduplicationScope(DeduplicationScope.MESSAGE_GROUP)
                .fifoThroughputLimit(FifoThroughputLimit.PER_MESSAGE_GROUP_ID)
                .visibilityTimeout(Duration.minutes(16))  // Must be >= Lambda timeout (15 min) + buffer
                .retentionPeriod(Duration.days(14))
                .deadLetterQueue(software.amazon.awscdk.services.sqs.DeadLetterQueue.builder()
                        .queue(Queue.Builder.create(this, "TTSProcessingDLQ")
                                .queueName("tts-dlq.fifo")
                                .fifo(true)
                                .build())
                        .maxReceiveCount(3)
                        .build())
                .build();

        // SQS FIFO Queue for stitching notifications
        final Queue stitchQueue = Queue.Builder.create(this, "StitchNotificationQueue")
                .queueName("stitch-notification-queue.fifo")
                .fifo(true)
                .contentBasedDeduplication(true)
                .visibilityTimeout(Duration.minutes(11))  // 10 min processing + buffer
                .build();

        // Dead Letter Queue for failed chapter processing
        final Queue chapterDLQ = Queue.Builder.create(this, "ChapterDLQ")
                .queueName("chapter-processing-dlq.fifo")
                .fifo(true)
                .contentBasedDeduplication(true)
                .retentionPeriod(Duration.days(14))
                .build();

        // SQS FIFO Queue for chapter processing messages with retry and DLQ
        final Queue chapterQueue = Queue.Builder.create(this, "ChapterQueue")
                .queueName("chapter-processing-queue.fifo")
                .fifo(true)
                .contentBasedDeduplication(true)
                .deduplicationScope(DeduplicationScope.MESSAGE_GROUP)
                .fifoThroughputLimit(FifoThroughputLimit.PER_MESSAGE_GROUP_ID)
                .visibilityTimeout(Duration.minutes(15))  // 10 min for text extraction + 5 min buffer
                .retentionPeriod(Duration.days(14))
                .deadLetterQueue(DeadLetterQueue.builder()
                        .queue(chapterDLQ)
                        .maxReceiveCount(3)  // Retry failed chapters 3 times before moving to DLQ
                        .build())
                .build();

        // Orchestrator Lambda - Analyzes PDF structure and queues chapters
        final Function orchestratorLambda = Function.Builder.create(this, "OrchestratorLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/orchestrator-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.of(
                        "GEMINI_API_KEY", System.getenv("GEMINI_API_KEY") != null ? System.getenv("GEMINI_API_KEY") : "",
                        "CHAPTER_QUEUE_URL", chapterQueue.getQueueUrl(),
                        "TEXT_BUCKET", geminiTextBucket.getBucketName()))
                .timeout(Duration.minutes(15))  // PDF upload and analysis can take time
                .memorySize(1024)
                .build();

        // Grant permissions for Orchestrator Lambda
        originalFileBucket.grantRead(orchestratorLambda);  // Read from original bucket where files are validated
        geminiUploadBucket.grantRead(orchestratorLambda);  // Keep for backward compatibility
        geminiTextBucket.grantPut(orchestratorLambda);  // For metadata storage
        chapterQueue.grantSendMessages(orchestratorLambda);

        // Chapter Text Extractor Lambda - Only extracts text and chunks (no TTS)
        // CRITICAL: Limited to 2 concurrent executions to prevent API quota exhaustion
        // Gemini API limit: 1M tokens/minute for gemini-3-pro
        // Each chapter: ~200k tokens
        // 2 concurrent × 200k = 400k tokens/minute (safely under 1M limit)
        final Function chapterTextExtractorLambda = Function.Builder.create(this, "ChapterTextExtractorLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/chapter-text-extractor-lambda"))
                .handler("handler.lambda_handler")  // Use main handler with retry logic
                .environment(Map.of(
                        "GEMINI_API_KEY", System.getenv("GEMINI_API_KEY") != null ? System.getenv("GEMINI_API_KEY") : "",
                        "TEXT_CHUNKS_BUCKET", textChunksBucket.getBucketName(),  // Use dedicated text chunks bucket
                        "TTS_QUEUE_URL", ttsQueue.getQueueUrl(),
                        "GEMINI_TEXT_MODEL", "gemini-3-pro-preview",
                        "MAX_TOKENS_PER_CHUNK", "4500"))  // Reduced chunk size
                .timeout(Duration.minutes(10))  // Text extraction and chunking only
                .memorySize(1024)  // Less memory needed without audio processing
                .reservedConcurrentExecutions(2)  // CRITICAL: Limit to 2 to prevent API quota exhaustion
                .build();

        // Grant permissions for Chapter Text Extractor Lambda
        geminiTextBucket.grantReadWrite(chapterTextExtractorLambda);
        textChunksBucket.grantReadWrite(chapterTextExtractorLambda);  // For storing text chunks
        ttsQueue.grantSendMessages(chapterTextExtractorLambda);

        // Add SQS trigger to Chapter Text Extractor Lambda with batch failure reporting
        // This enables automatic retry of failed messages via batchItemFailures response
        chapterTextExtractorLambda.addEventSource(SqsEventSource.Builder.create(chapterQueue)
                .batchSize(1)  // Process one chapter at a time for better error handling
                .reportBatchItemFailures(true)  // Enable partial batch failure responses for retry
                .build());

        // TTS Generation Lambda - Processes individual text chunks
        // Rate Limited: Gemini TTS API has 10,000 tokens/minute limit
        // With 2 concurrent executions × 4,500 tokens = 9,000 tokens/minute (safely under limit)
        final Function ttsGenerationLambda = Function.Builder.create(this, "TTSGenerationLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/tts-generation-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.of(
                        "GEMINI_API_KEY", System.getenv("GEMINI_API_KEY") != null ? System.getenv("GEMINI_API_KEY") : "",
                        "AUDIO_CHUNKS_BUCKET", audioChunksBucket.getBucketName(),
                        "TEXT_CHUNKS_BUCKET", textChunksBucket.getBucketName(),  // Use dedicated text chunks bucket
                        "STITCH_QUEUE_URL", stitchQueue.getQueueUrl(),
                        "GEMINI_TTS_MODEL", "gemini-2.5-pro-preview-tts",
                        "GEMINI_TTS_VOICE", "Charon",
                        "MAX_RETRY_ATTEMPTS", "5",
                        "INITIAL_WAIT", "10",
                        "MAX_WAIT", "120"))
                .timeout(Duration.minutes(15))  // Max timeout for large chunks
                .memorySize(2048)  // Higher memory for better performance
                .reservedConcurrentExecutions(2)  // Reduced from 20 to stay under 10k tokens/min rate limit
                .build();

        // Grant permissions for TTS Generation Lambda
        geminiTextBucket.grantRead(ttsGenerationLambda);
        textChunksBucket.grantRead(ttsGenerationLambda);  // For reading text chunks
        audioChunksBucket.grantPut(ttsGenerationLambda);
        stitchQueue.grantSendMessages(ttsGenerationLambda);

        // Add SQS trigger to TTS Generation Lambda
        ttsGenerationLambda.addEventSource(SqsEventSource.Builder.create(ttsQueue)
                .batchSize(1)  // Process one chunk at a time
                .build());

        // Audio Stitching Lambda - Concatenates audio chunks
        // Uses streaming approach to process chunks sequentially, reducing memory usage
        // from 3008MB to ~1331MB. Processes chunks one-by-one and writes to temp file
        // in /tmp (512MB available) before uploading final audio to S3.
        final Function audioStitchingLambda = Function.Builder.create(this, "AudioStitchingLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/audio-stitching-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.of(
                        "AUDIO_CHUNKS_BUCKET", audioChunksBucket.getBucketName(),
                        "PROCESSED_BUCKET_NAME", processedFileBucket.getBucketName(),
                        "TRACKING_TABLE", chunkTrackingTable.getTableName(),
                        "CROSSFADE_DURATION_MS", "50"))
                .timeout(Duration.minutes(10))  // Concatenation can take time
                .memorySize(3008)  // Set to max for safety, but streaming only uses ~1331MB
                .reservedConcurrentExecutions(5)
                .build();

        // Grant permissions for Audio Stitching Lambda
        audioChunksBucket.grantRead(audioStitchingLambda);
        audioChunksBucket.grantDelete(audioStitchingLambda);  // Clean up chunks after stitching
        processedFileBucket.grantPut(audioStitchingLambda);
        chunkTrackingTable.grantReadWriteData(audioStitchingLambda);

        // Add SQS trigger to Audio Stitching Lambda
        audioStitchingLambda.addEventSource(SqsEventSource.Builder.create(stitchQueue)
                .batchSize(1)
                .build());

        // S3 Event Notification: Trigger Orchestrator when PDF uploaded to original bucket (after validation)
        originalFileBucket.addEventNotification(
                EventType.OBJECT_CREATED,
                new LambdaDestination(orchestratorLambda),
                NotificationKeyFilter.builder()
                        .suffix(".pdf")
                        .build()
        );
    }

    public Function getValidationLambda() {
        return validationLambda;
    }

    public Function getPresignedUrlLambda() {
        return presignedUrlLambda;
    }
}
