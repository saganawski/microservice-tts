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
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscription;
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
        // TEXTRACT FLOW - AWS Textract for text extraction
        // Whole-book processing without chapter detection
        // ============================================================

        // Create bucket for Textract results (raw JSON + extracted text)
        final Bucket textractResultsBucket = Bucket.Builder.create(this, "TextractResultsBucket")
                .bucketName("textract-results-bucket" + accountNumber)
                .versioned(false)
                .lifecycleRules(List.of(software.amazon.awscdk.services.s3.LifecycleRule.builder()
                        .expiration(Duration.days(30))  // Clean up raw JSON after 30 days
                        .build()))
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

        // Create DynamoDB table for tracking chunk completion (job-based, not chapter-based)
        final Table chunkTrackingTable = Table.Builder.create(this, "AudioChunkTracking")
                .tableName("JobAudioChunkTracking")
                .partitionKey(Attribute.builder()
                        .name("job_id")  // Changed from chapter_key to job_id for whole-book processing
                        .type(AttributeType.STRING)
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .timeToLiveAttribute("ttl")
                .build();

        // SNS Topic for TTS validation failure alerts
        // Sends notifications when audio chunks fail validation after all retries
        final Topic validationAlertTopic = Topic.Builder.create(this, "ValidationAlertTopic")
                .topicName("tts-validation-alerts")
                .build();

        // Add email subscription for validation alerts (configure your email)
        // TODO: Update with your actual email address or remove if using different notification method
        validationAlertTopic.addSubscription(
                new EmailSubscription("ken@example.com")
        );

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

        // Dead Letter Queue for failed chunking operations
        final Queue chunkingDLQ = Queue.Builder.create(this, "ChunkingDLQ")
                .queueName("chunking-dlq.fifo")
                .fifo(true)
                .contentBasedDeduplication(true)
                .retentionPeriod(Duration.days(14))
                .build();

        // SQS FIFO Queue for text chunking operations
        final Queue chunkingQueue = Queue.Builder.create(this, "ChunkingQueue")
                .queueName("chunking-queue.fifo")
                .fifo(true)
                .contentBasedDeduplication(true)
                .visibilityTimeout(Duration.minutes(5))  // Chunking should be fast
                .retentionPeriod(Duration.days(14))
                .deadLetterQueue(DeadLetterQueue.builder()
                        .queue(chunkingDLQ)
                        .maxReceiveCount(3)
                        .build())
                .build();

        // Textract Extraction Lambda - Extracts all text from PDF using AWS Textract
        final Function textractExtractionLambda = Function.Builder.create(this, "TextractExtractionLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/textract-extraction-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.of(
                        "TEXTRACT_RESULTS_BUCKET", textractResultsBucket.getBucketName(),
                        "CHUNKING_QUEUE_URL", chunkingQueue.getQueueUrl()))
                .timeout(Duration.minutes(15))  // Textract can take several minutes for large PDFs
                .memorySize(2048)
                .build();

        // Grant permissions for Textract Extraction Lambda
        originalFileBucket.grantRead(textractExtractionLambda);
        textractResultsBucket.grantPut(textractExtractionLambda);
        chunkingQueue.grantSendMessages(textractExtractionLambda);
        textractExtractionLambda.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "textract:StartDocumentTextDetection",
                        "textract:GetDocumentTextDetection"))
                .resources(List.of("*"))
                .build());

        // Text Chunking Lambda - Splits full text into 4500-token chunks
        final Function textChunkingLambda = Function.Builder.create(this, "TextChunkingLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/text-chunking-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.of(
                        "TEXTRACT_RESULTS_BUCKET", textractResultsBucket.getBucketName(),
                        "TEXT_CHUNKS_BUCKET", textChunksBucket.getBucketName(),
                        "TTS_QUEUE_URL", ttsQueue.getQueueUrl(),
                        "MAX_TOKENS_PER_CHUNK", "4500"))
                .timeout(Duration.minutes(2))  // Chunking should be fast - just text splitting
                .memorySize(1024)
                .build();

        // Grant permissions for Text Chunking Lambda
        textractResultsBucket.grantRead(textChunkingLambda);
        textChunksBucket.grantPut(textChunkingLambda);
        ttsQueue.grantSendMessages(textChunkingLambda);

        // Add SQS trigger to Text Chunking Lambda
        textChunkingLambda.addEventSource(SqsEventSource.Builder.create(chunkingQueue)
                .batchSize(1)
                .build());

        // TTS Generation Lambda - Processes individual text chunks
        // Rate Limited: Gemini TTS API has 10,000 tokens/minute limit
        // With 2 concurrent executions × 4,500 tokens = 9,000 tokens/minute (safely under limit)
        final Function ttsGenerationLambda = Function.Builder.create(this, "TTSGenerationLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/tts-generation-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.ofEntries(
                        Map.entry("GEMINI_API_KEY", System.getenv("GEMINI_API_KEY") != null ? System.getenv("GEMINI_API_KEY") : ""),
                        Map.entry("AUDIO_CHUNKS_BUCKET", audioChunksBucket.getBucketName()),
                        Map.entry("TEXT_CHUNKS_BUCKET", textChunksBucket.getBucketName()),
                        Map.entry("STITCH_QUEUE_URL", stitchQueue.getQueueUrl()),
                        Map.entry("GEMINI_TTS_MODEL", "gemini-2.5-pro-preview-tts"),
                        Map.entry("GEMINI_TTS_VOICE", "Charon"),
                        Map.entry("MAX_RETRY_ATTEMPTS", "5"),
                        Map.entry("INITIAL_WAIT", "10"),
                        Map.entry("MAX_WAIT", "120"),
                        Map.entry("VALIDATION_ALERT_TOPIC_ARN", validationAlertTopic.getTopicArn())))
                .timeout(Duration.minutes(15))  // Max timeout for large chunks
                .memorySize(2048)  // Higher memory for better performance
                .reservedConcurrentExecutions(2)  // Reduced from 20 to stay under 10k tokens/min rate limit
                .build();

        // Grant permissions for TTS Generation Lambda
        textChunksBucket.grantRead(ttsGenerationLambda);  // For reading text chunks
        audioChunksBucket.grantPut(ttsGenerationLambda);
        stitchQueue.grantSendMessages(ttsGenerationLambda);
        validationAlertTopic.grantPublish(ttsGenerationLambda);  // For sending validation failure alerts

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

        // S3 Event Notification: Trigger Textract Extraction when PDF uploaded to original bucket (after validation)
        originalFileBucket.addEventNotification(
                EventType.OBJECT_CREATED,
                new LambdaDestination(textractExtractionLambda),
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
