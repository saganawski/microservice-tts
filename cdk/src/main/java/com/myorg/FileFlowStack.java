package com.myorg;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Size;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.eventsources.SqsEventSource;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.IBucket;
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
import software.amazon.awscdk.services.secretsmanager.ISecret;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.cloudwatch.actions.SnsAction;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.Schedule;
import software.amazon.awscdk.services.events.targets.LambdaFunction;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.amazon.awscdk.services.cognito.UserPool;
import software.amazon.awscdk.services.cognito.UserPoolClient;
import software.amazon.awscdk.services.cognito.SignInAliases;
import software.amazon.awscdk.services.cognito.AutoVerifiedAttrs;
import software.amazon.awscdk.services.cognito.PasswordPolicy;
import software.amazon.awscdk.services.cognito.AccountRecovery;
import software.amazon.awscdk.services.cognito.UserPoolEmail;
import software.amazon.awscdk.services.cognito.AuthFlow;
import software.amazon.awscdk.CfnOutput;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;


public class FileFlowStack extends Stack {
    private final Function validationLambda;
    private final Function presignedUrlLambda;
    private final Function jobStatusLambda;
    private final UserPool userPool;
    private final UserPoolClient userPoolClient;

    public FileFlowStack(final Construct scope, final String id, final StackProps props) {

        super(scope, id, props);

        final String accountNumber = "272765753210";

        final Bucket originalFileBucket = Bucket.Builder.create(this, "OriginalFileBucket")
                .bucketName("original-file-bucket" + accountNumber)
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

        // DynamoDB table for job status tracking (frontend polling)
        final Table jobStatusTable = Table.Builder.create(this, "JobStatusTable")
                .tableName("JobStatus")
                .partitionKey(Attribute.builder()
                        .name("job_id")
                        .type(AttributeType.STRING)
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .timeToLiveAttribute("ttl")
                .build();

        // Grant FileValidationLambda access to JobStatus table (for storing user email)
        validationLambda.addEnvironment("JOB_STATUS_TABLE", jobStatusTable.getTableName());
        jobStatusTable.grantReadWriteData(validationLambda);

        // Job Status Lambda - handles GET /status/{jobId}
        jobStatusLambda = Function.Builder.create(this, "JobStatusLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/job-status-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.of(
                        "JOB_STATUS_TABLE", jobStatusTable.getTableName()))
                .timeout(Duration.seconds(10))
                .memorySize(256)
                .build();

        // Grant read access to job status lambda
        jobStatusTable.grantReadData(jobStatusLambda);

        // SNS Topic for TTS validation failure alerts
        // Sends notifications when audio chunks fail validation after all retries
        final Topic validationAlertTopic = Topic.Builder.create(this, "ValidationAlertTopic")
                .topicName("tts-validation-alerts")
                .build();

        // Add email subscription for validation alerts (configure your email)
        // TODO: Update with your actual email address or remove if using different notification method
        validationAlertTopic.addSubscription(
                new EmailSubscription("kennethsaganski@gmail.com")
        );

        // ============================================================
        // JOB COMPLETION NOTIFICATION SYSTEM
        // ============================================================

        // SNS Topic for job completion notifications
        // AudioStitchingLambda publishes here when all parts are stitched
        final Topic jobCompletionTopic = Topic.Builder.create(this, "JobCompletionTopic")
                .topicName("tts-job-complete")
                .build();

        // Notification Lambda - Generates presigned URLs and sends email notifications via SES
        final Function notificationLambda = Function.Builder.create(this, "NotificationLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/notification-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.ofEntries(
                        Map.entry("PROCESSED_BUCKET_NAME", processedFileBucket.getBucketName()),
                        Map.entry("NOTIFICATION_TOPIC_ARN", jobCompletionTopic.getTopicArn()),
                        Map.entry("PRESIGNED_URL_EXPIRY_HOURS", "72"),
                        Map.entry("SES_FROM_EMAIL", "kennethsaganski@gmail.com"),
                        Map.entry("JOB_STATUS_TABLE", jobStatusTable.getTableName())))
                .timeout(Duration.minutes(1))
                .memorySize(256)
                .build();

        // Grant permissions for Notification Lambda
        processedFileBucket.grantRead(notificationLambda);
        jobCompletionTopic.grantPublish(notificationLambda);
        jobStatusTable.grantReadData(notificationLambda);

        // Grant SES permissions for sending emails directly to users
        notificationLambda.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("ses:SendEmail", "ses:SendRawEmail"))
                .resources(List.of("*"))
                .build());

        // Subscribe Notification Lambda to job completion topic
        jobCompletionTopic.addSubscription(new LambdaSubscription(notificationLambda));

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

        // Reference Gemini API key from Secrets Manager (used by emotion + TTS lambdas)
        final ISecret geminiApiKeySecret = Secret.fromSecretNameV2(this, "GeminiApiKeySecret",
                "microservice-tts/gemini-api-key");

        // ============================================================
        // EMOTION PREPROCESSING - Tags text with Orpheus emotion cues
        // Sits between Textract extraction and text chunking
        // ============================================================

        // Dead Letter Queue for failed emotion processing
        final Queue emotionDLQ = Queue.Builder.create(this, "EmotionDLQ")
                .queueName("emotion-dlq.fifo")
                .fifo(true)
                .contentBasedDeduplication(true)
                .retentionPeriod(Duration.days(14))
                .build();

        // SQS FIFO Queue for emotion preprocessing
        final Queue emotionQueue = Queue.Builder.create(this, "EmotionQueue")
                .queueName("emotion-queue.fifo")
                .fifo(true)
                .contentBasedDeduplication(true)
                .visibilityTimeout(Duration.minutes(16))  // Must be >= Lambda timeout + buffer
                .retentionPeriod(Duration.days(14))
                .deadLetterQueue(DeadLetterQueue.builder()
                        .queue(emotionDLQ)
                        .maxReceiveCount(3)
                        .build())
                .build();

        // Emotion Preprocessing Lambda - Adds Orpheus emotion tags to full text
        final Function emotionPreprocessingLambda = Function.Builder.create(this, "EmotionPreprocessingLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/emotion-preprocessing"))
                .handler("handler.lambda_handler")
                .environment(Map.ofEntries(
                        Map.entry("GEMINI_SECRET_NAME", "microservice-tts/gemini-api-key"),
                        Map.entry("EMOTION_LEVEL", "subtle"),
                        Map.entry("TEXTRACT_RESULTS_BUCKET", textractResultsBucket.getBucketName()),
                        Map.entry("CHUNKING_QUEUE_URL", chunkingQueue.getQueueUrl())))
                .timeout(Duration.minutes(15))  // Gemini calls for long texts can take time
                .memorySize(1024)
                .build();

        // Grant permissions for Emotion Preprocessing Lambda
        geminiApiKeySecret.grantRead(emotionPreprocessingLambda);
        textractResultsBucket.grantRead(emotionPreprocessingLambda);
        textractResultsBucket.grantPut(emotionPreprocessingLambda);
        chunkingQueue.grantSendMessages(emotionPreprocessingLambda);

        // Add SQS trigger to Emotion Preprocessing Lambda
        emotionPreprocessingLambda.addEventSource(SqsEventSource.Builder.create(emotionQueue)
                .batchSize(1)
                .build());

        // Textract Extraction Lambda - Extracts all text from PDF using AWS Textract
        // Now sends to emotion queue instead of directly to chunking queue
        final Function textractExtractionLambda = Function.Builder.create(this, "TextractExtractionLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/textract-extraction-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.of(
                        "TEXTRACT_RESULTS_BUCKET", textractResultsBucket.getBucketName(),
                        "EMOTION_QUEUE_URL", emotionQueue.getQueueUrl(),
                        "CHUNKING_QUEUE_URL", chunkingQueue.getQueueUrl(),
                        "SKIP_EMOTION", "true",  // MOSS-TTS doesn't use emotion tags
                        "JOB_STATUS_TABLE", jobStatusTable.getTableName()))
                .timeout(Duration.minutes(15))  // Textract can take several minutes for large PDFs
                .memorySize(2048)
                .build();

        // Grant permissions for Textract Extraction Lambda
        originalFileBucket.grantRead(textractExtractionLambda);
        textractResultsBucket.grantPut(textractExtractionLambda);
        emotionQueue.grantSendMessages(textractExtractionLambda);
        chunkingQueue.grantSendMessages(textractExtractionLambda);  // For SKIP_EMOTION=true (MOSS)
        jobStatusTable.grantReadWriteData(textractExtractionLambda);
        textractExtractionLambda.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "textract:StartDocumentTextDetection",
                        "textract:GetDocumentTextDetection"))
                .resources(List.of("*"))
                .build());

        // Text Chunking Lambda - Splits full text into token-sized chunks (1500 for Orpheus, 4500 for Gemini)
        final Function textChunkingLambda = Function.Builder.create(this, "TextChunkingLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/text-chunking-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.ofEntries(
                        Map.entry("TEXTRACT_RESULTS_BUCKET", textractResultsBucket.getBucketName()),
                        Map.entry("TEXT_CHUNKS_BUCKET", textChunksBucket.getBucketName()),
                        Map.entry("TTS_QUEUE_URL", ttsQueue.getQueueUrl()),
                        Map.entry("TTS_PROVIDER", "moss"),
                        Map.entry("JOB_STATUS_TABLE", jobStatusTable.getTableName())))
                .timeout(Duration.minutes(2))  // Chunking should be fast - just text splitting
                .memorySize(1024)
                .build();

        // Grant permissions for Text Chunking Lambda
        textractResultsBucket.grantRead(textChunkingLambda);
        textChunksBucket.grantPut(textChunkingLambda);
        ttsQueue.grantSendMessages(textChunkingLambda);
        jobStatusTable.grantReadWriteData(textChunkingLambda);

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
                        Map.entry("GEMINI_SECRET_NAME", "microservice-tts/gemini-api-key"),
                        Map.entry("AUDIO_CHUNKS_BUCKET", audioChunksBucket.getBucketName()),
                        Map.entry("TEXT_CHUNKS_BUCKET", textChunksBucket.getBucketName()),
                        Map.entry("STITCH_QUEUE_URL", stitchQueue.getQueueUrl()),
                        Map.entry("GEMINI_TTS_MODEL", "gemini-2.5-pro-preview-tts"),
                        Map.entry("GEMINI_TTS_VOICE", "Charon"),
                        Map.entry("TTS_PROVIDER", "moss"),
                        Map.entry("MOSS_SSM_PARAM", "/microservice-tts/moss-api-url"),
                        Map.entry("ORPHEUS_SSM_PARAM", "/microservice-tts/orpheus-api-url"),
                        Map.entry("ORPHEUS_VOICE", "tara"),
                        Map.entry("MAX_RETRY_ATTEMPTS", "5"),
                        Map.entry("INITIAL_WAIT", "10"),
                        Map.entry("MAX_WAIT", "120"),
                        Map.entry("VALIDATION_ALERT_TOPIC_ARN", validationAlertTopic.getTopicArn()),
                        Map.entry("JOB_STATUS_TABLE", jobStatusTable.getTableName())))
                .timeout(Duration.minutes(15))  // Max timeout for large chunks
                .memorySize(2048)  // Higher memory for better performance
                .reservedConcurrentExecutions(1)  // Reduced from 20 to stay under 10k tokens/min rate limit
                .build();

        // Grant permissions for TTS Generation Lambda
        geminiApiKeySecret.grantRead(ttsGenerationLambda);  // Read API key from Secrets Manager
        textChunksBucket.grantRead(ttsGenerationLambda);  // For reading text chunks
        audioChunksBucket.grantPut(ttsGenerationLambda);
        stitchQueue.grantSendMessages(ttsGenerationLambda);
        validationAlertTopic.grantPublish(ttsGenerationLambda);  // For sending validation failure alerts
        jobStatusTable.grantReadWriteData(ttsGenerationLambda);

        // Grant TTS Lambda read access to voice reference bucket (for MOSS voice cloning WAVs)
        final IBucket voiceReferenceBucket = Bucket.fromBucketName(this, "VoiceReferenceBucket",
                "tts-eval-data-272765753210");
        voiceReferenceBucket.grantRead(ttsGenerationLambda, "voices/*");

        // Grant TTS Lambda permission to read Orpheus URL from SSM Parameter Store
        ttsGenerationLambda.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("ssm:GetParameter"))
                .resources(List.of("arn:aws:ssm:us-east-1:" + accountNumber + ":parameter/microservice-tts/*"))
                .build());

        // Add SQS trigger to TTS Generation Lambda
        ttsGenerationLambda.addEventSource(SqsEventSource.Builder.create(ttsQueue)
                .batchSize(1)  // Process one chunk at a time
                .build());

        // ============================================================
        // EC2 AUTO-START/STOP FOR ORPHEUS GPU INSTANCE (TICKET-011)
        // Saves ~$680/mo by only running the g5.xlarge when jobs are queued.
        // - CloudWatch Alarm on TTS queue depth triggers start
        // - EventBridge scheduled rule (every 5 min) triggers idle check
        // - SSM Parameter Store holds the dynamic Orpheus API URL
        // ============================================================

        final String ec2InstanceId = "i-0ed07110e0cc39f9a";

        // SSM Parameters for Orpheus dynamic IP and last activity tracking
        final StringParameter orpheusUrlParam = StringParameter.Builder.create(this, "OrpheusApiUrlParam")
                .parameterName("/microservice-tts/orpheus-api-url")
                .stringValue("http://0.0.0.0:8000")  // Placeholder; EC2 manager updates on start
                .description("Dynamic Orpheus TTS API URL (updated on EC2 start)")
                .build();

        final StringParameter lastActivityParam = StringParameter.Builder.create(this, "OrpheusLastActivityParam")
                .parameterName("/microservice-tts/orpheus-last-activity")
                .stringValue("0")
                .description("Unix timestamp of last TTS queue activity")
                .build();

        // SNS Topic for CloudWatch Alarm -> EC2 Manager Lambda
        final Topic ec2AlarmTopic = Topic.Builder.create(this, "EC2StartAlarmTopic")
                .topicName("ec2-orpheus-start-alarm")
                .build();

        // EC2 Manager Lambda - starts/stops Orpheus GPU instance
        final Function ec2ManagerLambda = Function.Builder.create(this, "EC2ManagerLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/ec2-manager-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.of(
                        "EC2_INSTANCE_ID", ec2InstanceId,
                        "SSM_PARAM_NAME", "/microservice-tts/orpheus-api-url",
                        "SSM_LAST_ACTIVITY_PARAM", "/microservice-tts/orpheus-last-activity",
                        "TTS_QUEUE_URL", ttsQueue.getQueueUrl(),
                        "IDLE_TIMEOUT_MINUTES", "15",
                        "HEALTH_CHECK_TIMEOUT", "180"))
                .timeout(Duration.minutes(5))  // Needs time for EC2 start + health polling
                .memorySize(256)
                .build();

        // IAM: EC2 start/stop/describe
        ec2ManagerLambda.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "ec2:StartInstances",
                        "ec2:StopInstances",
                        "ec2:DescribeInstances"))
                .resources(List.of(
                        "arn:aws:ec2:us-east-1:" + accountNumber + ":instance/" + ec2InstanceId))
                .build());
        // DescribeInstances also needs wildcard resource
        ec2ManagerLambda.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("ec2:DescribeInstances"))
                .resources(List.of("*"))
                .build());

        // IAM: SSM read/write for Orpheus URL and last activity
        ec2ManagerLambda.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("ssm:GetParameter", "ssm:PutParameter"))
                .resources(List.of("arn:aws:ssm:us-east-1:" + accountNumber + ":parameter/microservice-tts/*"))
                .build());

        // IAM: SQS read attributes for queue depth check
        ttsQueue.grantSendMessages(ec2ManagerLambda);  // For GetQueueAttributes
        ec2ManagerLambda.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("sqs:GetQueueAttributes"))
                .resources(List.of(ttsQueue.getQueueArn()))
                .build());

        // Subscribe EC2 Manager Lambda to alarm SNS topic
        ec2AlarmTopic.addSubscription(new LambdaSubscription(ec2ManagerLambda));

        // CloudWatch Alarm: TTS queue has messages -> start EC2 instance
        Alarm ttsQueueAlarm = Alarm.Builder.create(this, "TTSQueueDepthAlarm")
                .alarmName("orpheus-ec2-start-on-queue-depth")
                .alarmDescription("Triggers EC2 Orpheus instance start when TTS messages are queued")
                .metric(ttsQueue.metricApproximateNumberOfMessagesVisible()
                        .with(software.amazon.awscdk.services.cloudwatch.MetricOptions.builder()
                                .period(Duration.minutes(1))
                                .statistic("Maximum")
                                .build()))
                .threshold(1)
                .evaluationPeriods(1)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD)
                .treatMissingData(TreatMissingData.NOT_BREACHING)
                .build();
        ttsQueueAlarm.addAlarmAction(new SnsAction(ec2AlarmTopic));

        // EventBridge Rule: every 5 minutes, trigger idle check
        Rule idleCheckRule = Rule.Builder.create(this, "OrpheusIdleCheckRule")
                .ruleName("orpheus-ec2-idle-check")
                .description("Check every 5 min if Orpheus EC2 should be stopped due to inactivity")
                .schedule(Schedule.rate(Duration.minutes(5)))
                .build();
        idleCheckRule.addTarget(new LambdaFunction(ec2ManagerLambda));

        // Audio Stitching Lambda - Concatenates audio chunks
        // Uses streaming approach to process chunks sequentially, reducing memory usage
        // from 3008MB to ~1331MB. Processes chunks one-by-one and writes to temp file
        // in /tmp (4GB ephemeral storage) before uploading final audio to S3.
        final Function audioStitchingLambda = Function.Builder.create(this, "AudioStitchingLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/audio-stitching-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.ofEntries(
                        Map.entry("AUDIO_CHUNKS_BUCKET", audioChunksBucket.getBucketName()),
                        Map.entry("PROCESSED_BUCKET_NAME", processedFileBucket.getBucketName()),
                        Map.entry("TRACKING_TABLE", chunkTrackingTable.getTableName()),
                        Map.entry("CROSSFADE_DURATION_MS", "50"),
                        Map.entry("JOB_COMPLETION_TOPIC_ARN", jobCompletionTopic.getTopicArn()),
                        Map.entry("JOB_STATUS_TABLE", jobStatusTable.getTableName())))
                .timeout(Duration.minutes(10))  // Concatenation can take time
                .memorySize(3008)  // Set to max for safety, but streaming only uses ~1331MB
                .ephemeralStorageSize(Size.gibibytes(4))  // 4GB /tmp for stitching large audio files
                .reservedConcurrentExecutions(5)
                .build();

        // Grant permissions for Audio Stitching Lambda
        audioChunksBucket.grantRead(audioStitchingLambda);
        audioChunksBucket.grantDelete(audioStitchingLambda);  // Clean up chunks after stitching
        processedFileBucket.grantPut(audioStitchingLambda);
        chunkTrackingTable.grantReadWriteData(audioStitchingLambda);
        jobCompletionTopic.grantPublish(audioStitchingLambda);  // For sending completion notifications
        jobStatusTable.grantReadWriteData(audioStitchingLambda);

        // Add SQS trigger to Audio Stitching Lambda
        audioStitchingLambda.addEventSource(SqsEventSource.Builder.create(stitchQueue)
                .batchSize(1)
                .build());

        // S3 Event Notification: Trigger extraction when file uploaded to original bucket (after validation)
        // PDF files → Textract extraction
        originalFileBucket.addEventNotification(
                EventType.OBJECT_CREATED,
                new LambdaDestination(textractExtractionLambda),
                NotificationKeyFilter.builder()
                        .suffix(".pdf")
                        .build()
        );
        // EPUB files → ebooklib extraction (same Lambda, detects file type)
        originalFileBucket.addEventNotification(
                EventType.OBJECT_CREATED,
                new LambdaDestination(textractExtractionLambda),
                NotificationKeyFilter.builder()
                        .suffix(".epub")
                        .build()
        );

        // ============================================================
        // AUDIO QUALITY AUDIT SYSTEM
        // Uses OpenAI Whisper API to transcribe audio and compare
        // against source text using Word Error Rate (WER)
        // ============================================================

        // Audit Results Bucket - stores WER reports and transcriptions
        final Bucket auditResultsBucket = Bucket.Builder.create(this, "AuditResultsBucket")
                .bucketName("audit-results-bucket" + accountNumber)
                .versioned(false)
                .lifecycleRules(List.of(software.amazon.awscdk.services.s3.LifecycleRule.builder()
                        .expiration(Duration.days(90))  // Keep audit results for 90 days
                        .build()))
                .build();

        // SNS Topic for audit completion notifications
        final Topic auditNotificationTopic = Topic.Builder.create(this, "AuditNotificationTopic")
                .topicName("tts-audit-complete")
                .build();

        // Subscribe email to audit notifications
        auditNotificationTopic.addSubscription(
                new EmailSubscription("kennethsaganski@gmail.com")
        );

        // SQS FIFO Queue for audit requests
        final Queue auditQueue = Queue.Builder.create(this, "AuditQueue")
                .queueName("audit-queue.fifo")
                .fifo(true)
                .contentBasedDeduplication(true)
                .visibilityTimeout(Duration.minutes(16))  // Match Lambda timeout + buffer
                .retentionPeriod(Duration.days(7))
                .build();

        // Audit Lambda - Transcribes audio and calculates WER
        // Uses OpenAI Whisper API ($0.006/minute)
        final Function auditLambda = Function.Builder.create(this, "AuditLambda")
                .runtime(Runtime.PYTHON_3_12)
                .code(Code.fromAsset("lambdas/audit-lambda"))
                .handler("handler.lambda_handler")
                .environment(Map.of(
                        "OPENAI_API_KEY", System.getenv("OPENAI_API_KEY") != null ? System.getenv("OPENAI_API_KEY") : "",
                        "TEXT_CHUNKS_BUCKET", textChunksBucket.getBucketName(),
                        "AUDIO_CHUNKS_BUCKET", audioChunksBucket.getBucketName(),
                        "AUDIT_RESULTS_BUCKET", auditResultsBucket.getBucketName(),
                        "AUDIT_SNS_TOPIC_ARN", auditNotificationTopic.getTopicArn(),
                        "WHISPER_MODEL", "whisper-1"))
                .timeout(Duration.minutes(15))  // Long timeout for multiple chunk transcriptions
                .memorySize(1024)
                .ephemeralStorageSize(Size.gibibytes(2))  // For downloading audio files
                .build();

        // Grant permissions for Audit Lambda
        textChunksBucket.grantRead(auditLambda);
        audioChunksBucket.grantRead(auditLambda);
        auditResultsBucket.grantPut(auditLambda);
        auditNotificationTopic.grantPublish(auditLambda);

        // Add SQS trigger to Audit Lambda
        auditLambda.addEventSource(SqsEventSource.Builder.create(auditQueue)
                .batchSize(1)  // Process one audit request at a time
                .build());

        // ============================================================
        // COGNITO USER POOL - Authentication (TICKET-016)
        // ============================================================

        userPool = UserPool.Builder.create(this, "TtsUserPool")
                .userPoolName("tts-audiobook-users")
                .selfSignUpEnabled(true)
                .signInAliases(SignInAliases.builder()
                        .email(true)
                        .build())
                .autoVerify(AutoVerifiedAttrs.builder()
                        .email(true)
                        .build())
                .passwordPolicy(PasswordPolicy.builder()
                        .minLength(8)
                        .requireLowercase(true)
                        .requireUppercase(true)
                        .requireDigits(true)
                        .requireSymbols(false)
                        .build())
                .accountRecovery(AccountRecovery.EMAIL_ONLY)
                .email(UserPoolEmail.withCognito())
                .build();

        userPoolClient = UserPoolClient.Builder.create(this, "TtsUserPoolClient")
                .userPool(userPool)
                .userPoolClientName("tts-frontend-client")
                .authFlows(AuthFlow.builder()
                        .userPassword(true)
                        .userSrp(true)
                        .build())
                .build();

        // Output Cognito IDs for frontend configuration
        CfnOutput.Builder.create(this, "UserPoolId")
                .value(userPool.getUserPoolId())
                .description("Cognito User Pool ID")
                .build();

        CfnOutput.Builder.create(this, "UserPoolClientId")
                .value(userPoolClient.getUserPoolClientId())
                .description("Cognito User Pool Client ID")
                .build();
    }

    public Function getValidationLambda() {
        return validationLambda;
    }

    public Function getPresignedUrlLambda() {
        return presignedUrlLambda;
    }

    public Function getJobStatusLambda() {
        return jobStatusLambda;
    }

    public UserPool getUserPool() {
        return userPool;
    }

    public UserPoolClient getUserPoolClient() {
        return userPoolClient;
    }
}
